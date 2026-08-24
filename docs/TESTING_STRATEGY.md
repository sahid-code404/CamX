# Testing Strategy

## Pure JVM tests

Test value validation, topology permutation determinism and conservative separation, state transition
legality, typed failure policy, selection/session/capture generation rejection, FPS resolution,
geometry, bounded actual-FPS metrics, trace overflow, RAW pairing/closure, DNG orientation,
MediaStore rollback, cache codecs, settings memory-first behavior, and OTA rejection matrices.

## Native host and Android ABI tests

Host C++ compiles with C++20, `-Wall -Wextra -Werror -pedantic` and exercises bounded timestamp,
trace, counters, and buffer-pool ownership. Follow-up CI adds ASan/UBSan and fuzz targets for binary
metadata. Gradle compiles JNI for arm/arm64/x86/x86_64 and validates archive contents/exports.

## Instrumentation and lifecycle

Future instrumentation fakes platform callbacks to permute same-route stale opens, surface replace,
latest-wins switching, pause, cancellation, RAW restore, and MediaStore failures. Compose tests verify
permission/error actions, accessibility, and stable AndroidView identity. Macrobenchmark/profile
builds measure startup and switching but never replace debug OTA.

## Physical hardware

Only `HARDWARE_ACCEPTANCE.md` establishes actual support. CI language is restricted to build/policy/
ownership correctness and cannot claim lens, RAW, orientation, FPS, or leak compatibility.
