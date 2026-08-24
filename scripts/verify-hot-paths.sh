#!/usr/bin/env bash
set -euo pipefail

readonly root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"
failures=0
readonly camera_root="app/src/main/java/com/sahidcode404/camx/core/camera"

reject() {
  local label="$1"
  local pattern="$2"
  shift 2
  local matches
  if matches="$(rg --line-number --pcre2 "$pattern" "$@")"; then
    echo "Hot-path violation: $label" >&2
    echo "$matches" >&2
    failures=$((failures + 1))
  else
    local status=$?
    if ((status != 1)); then
      echo "Hot-path guard failed to scan: $label (rg exit $status)" >&2
      failures=$((failures + 1))
    fi
  fi
}

reject 'global coroutine scope' '\bGlobalScope\b' --glob '*.kt' app/src
reject 'blocking coroutine bridge' '\brunBlocking\b' --glob '*.kt' app/src
reject 'sleep-based synchronization' '\bThread\s*\.\s*sleep\s*\(' --glob '*.kt' --glob '*.java' app/src
reject 'unbounded Java executor factory' \
  '\bExecutors\.(?:newCachedThreadPool|newFixedThreadPool|newSingleThreadExecutor)\s*\(' \
  --glob '*.kt' --glob '*.java' app/src/main
reject 'DataStore in camera session/runtime/preview/raw hot boundary' '\bDataStore\b|\.data\.first\s*\(' \
  --glob '*.kt' "$camera_root/session" "$camera_root/runtime" "$camera_root/preview" "$camera_root/raw"
reject 'network API in session startup boundary' '\b(?:HttpURLConnection|URLConnection|OkHttpClient|URL)\b' \
  --glob '*.kt' "$camera_root/session" "$camera_root/runtime"
reject 'frame-by-frame JSON formatting' '\b(?:Json|JSONObject|encodeToString)\b' \
  --glob '*.kt' "$camera_root/preview" "$camera_root/trace"

((failures == 0)) || exit 1
echo 'Hot-path verification passed.'
