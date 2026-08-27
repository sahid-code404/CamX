#!/usr/bin/env bash
set -euo pipefail

readonly root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"
readonly ownership="docs/RESOURCE_OWNERSHIP.md"
readonly android_owner="app/src/main/java/com/sahidcode404/camx/core/camera/session/AndroidCameraOwnerPlatform.kt"
readonly dng_writer="app/src/main/java/com/sahidcode404/camx/core/camera/raw/AndroidSensorDngWriter.kt"
readonly media_transaction="app/src/main/java/com/sahidcode404/camx/core/camera/raw/MediaStoreTransaction.kt"

for resource in CameraDevice CameraCaptureSession Surface ImageReader 'RAW `Image`' \
  'RAW transaction token' 'Sensor DNG writer' 'DNG output stream' AImage AHardwareBuffer \
  'MediaStore row' 'callback thread'; do
  rg --fixed-strings --quiet "$resource" "$ownership" || {
    echo "Resource ownership entry missing: $resource" >&2
    exit 1
  }
done

for requirement in 'ImageReader.newInstance(' 'RAW_MAX_IMAGES = 2' \
  'session.capture(' 'rawBuilder.addTarget(rawSurface)'; do
  rg --fixed-strings --quiet "$requirement" "$android_owner" || {
    echo "One-shot RAW ownership requirement missing: $requirement" >&2
    exit 1
  }
done

for requirement in 'DngCreator(' 'creator.writeImage(counter, image.androidImage)' \
  'validateReopenedDng(uri, bytes, writeIsActive)' 'publicationPermit.claim()'; do
  rg --fixed-strings --quiet "$requirement" \
    "$dng_writer" app/src/main/java/com/sahidcode404/camx/core/camera/session/CameraSessionControllerRaw.kt || {
    echo "Sensor DNG ownership requirement missing: $requirement" >&2
    exit 1
  }
done

for requirement in 'class RawPublicationPermit' 'fun claim()' 'fun revoke()' \
  'if (!authorizePublish())' 'publish(row)'; do
  rg --fixed-strings --quiet "$requirement" "$media_transaction" || {
    echo "Publication boundary requirement missing: $requirement" >&2
    exit 1
  }
done

if rg --line-number '\bobject\s+(?:RawCaptureRegistry|RawSessionMode)\b' app/src/main/java; then
  echo 'Resource ownership violation: process-global RAW/session registry.' >&2
  exit 1
fi

for contract in 'class CameraSessionOutputPlan private constructor' \
  'RAW output is transaction-only' 'fun temporaryRaw('; do
  rg --fixed-strings --quiet "$contract" \
    app/src/main/java/com/sahidcode404/camx/core/camera/session/CameraOutputPlan.kt || {
    echo "Typed output ownership contract missing: $contract" >&2
    exit 1
  }
done

echo 'Resource ownership verification passed.'
