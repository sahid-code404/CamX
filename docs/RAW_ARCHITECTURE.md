# RAW Transaction Architecture

RAW is a bounded state transition beneath the sole session owner, not a second camera engine. CAMX-108
implements the public `RAW_SENSOR` one-shot acquisition primitive; it does not implement burst capture,
computational reconstruction, processed-source fallback, or RAW video. Those later contracts are frozen
separately in [`COMPUTATIONAL_RAW_ARCHITECTURE.md`](COMPUTATIONAL_RAW_ARCHITECTURE.md).

```text
PREVIEWING -> CONFIGURING_RAW -> CAPTURING_RAW -> PAIRING_RAW
           -> WRITING_DNG -> RESTORING_PREVIEW -> PREVIEWING
```

At shutter, `RawCaptureContext` snapshots capture token, selection/session generations, canonical and
profile fingerprints, route/open/physical target, preview-surface identity, display rotation, sensor
orientation, facing, exact RAW representation and dimensions, timestamp basis, admission time,
deadline, and timeout. Later code may not query selection or display state to reconstruct this identity.

The owner retains the active `CameraDevice`, replaces preview-only with a token-bound temporary
preview-plus-RAW session, submits exactly one non-repeating still request, and destroys the RAW output
before restoring preview-only. Idle preview has no RAW reader. The Android reader uses `maxImages = 2`;
the one-shot pairer has at most two pending entries and matches `Image.timestamp` to
`CaptureResult.SENSOR_TIMESTAMP` by exact positive equality in either callback order. Duplicate,
invalid, overflowed, timed-out, cancelled, and stale images close deterministically.
`CameraSessionOutputPlan` has only `previewOnly` and token-bound `temporaryRaw` factories, while
`CameraOutputBinding` rejects RAW with a repeating lifetime; CI unit tests encode this invariant.
One shutter-time deadline watchdog bounds temporary-session configuration and acquisition; pairing
does not receive a fresh timeout after configuration. The watchdog is cancelled when the exact pair
transfers to I/O, where the single blocking Android DNG operation remains bounded to that one image.
The writer checks coroutine ownership before each output, flush, and reopen-validation chunk;
cancellation closes the descriptor, deletes the still-pending row, and closes the transferred image.
Preview-only restoration keeps the controller in `RESTORING_PREVIEW` until a first frame is verified.
Both restore configuration and first-frame waits are bounded, with one retry; pause, switch, surface
loss, or shutdown invalidates their permits, and late sessions/frames cannot adopt newer ownership.

The paired `Image` transfers once to `AndroidSensorDngWriter` on its I/O dispatcher. `DngCreator`
receives the exact profile `CameraCharacteristics` and exact matched capture metadata; a physical
route uses the physical result contained by that same `TotalCaptureResult`. TIFF orientation derives
only from shutter context. CamX does not copy, rotate, mirror, demosaic, resize, denoise, sharpen,
tone-map, or otherwise change RAW samples.

`MediaStoreTransaction` inserts a pending destination, writes and closes, requires a positive byte
count, reopens with a bounded buffer, checks a basic TIFF/DNG header and exact written byte count,
atomically claims publication authority, and then publishes. That one-time claim immediately before
`publish` is the cancellation/commit boundary:
revocation that wins first deletes the pending row; a claim that wins first owns the publication
attempt. Pre-Android-10 uses a hidden pending display name and requires the API-gated legacy storage
permission; Android 10+ uses `IS_PENDING`. Every pre-commit failure attempts deletion. Cleanup failure
retains a bounded pending-row recovery identity in diagnostics.

Storage and DNG-interoperability failures never change camera or topology trust. Only a genuine
temporary-session configuration rejection marks the exact profile's runtime RAW path unavailable.
CAMX-108 does not automatically substitute a sibling profile. A later explicit selection may use an
eligible profile only under its own immutable identity; cross-canonical fallback is forbidden.

This one-shot path is the future `SensorDngWriter` acquisition boundary. It produces public,
interpretable sensor-domain evidence with no CamX sample-changing processing, not a
`ComputationalNegative`. JVM tests validate ownership, transaction, pairing, and orientation
semantics; physical Android capture plus a real RAW decoder remains required for DNG hardware
acceptance.
