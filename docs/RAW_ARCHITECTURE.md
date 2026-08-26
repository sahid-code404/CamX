# RAW Transaction Architecture

RAW is a bounded state transition beneath the sole session owner, not a second camera engine. CAMX-108
is the current implementation frontier and establishes this trustworthy one-shot acquisition primitive;
it does not implement burst capture, computational reconstruction, or RAW video. Those later contracts
are frozen separately in [`COMPUTATIONAL_RAW_ARCHITECTURE.md`](COMPUTATIONAL_RAW_ARCHITECTURE.md).

```text
PREVIEWING -> CONFIGURING_RAW -> CAPTURING_RAW -> PAIRING_RAW
           -> WRITING_DNG -> RESTORING_PREVIEW -> PREVIEWING
```

At shutter, `RawCaptureContext` snapshots capture token, selection/session generations, canonical and
profile fingerprints, route, display rotation, sensor orientation, facing, RAW size, and timeout.
Later code may not query selection or display state to reconstruct this identity.

The owner retains CameraDevice, replaces the preview session with a temporary supported session,
submits exactly one RAW request, and destroys RAW output before restoring preview-only. A bounded
`RawTimestampPairer` matches exact positive `SENSOR_TIMESTAMP` values in either callback order.
Duplicate, invalid, overflowed, timed-out, cancelled, and stale images close deterministically.
`CameraSessionOutputPlan` has only `previewOnly` and token-bound `temporaryRaw` factories, while
`CameraOutputBinding` rejects RAW with a repeating lifetime; CI unit tests encode this invariant.

The paired image transfers once to the sensor-DNG I/O boundary. `MediaStoreTransaction` inserts pending, writes,
publishes on success, and attempts deletion on every failure. A failed cleanup is attached to the
primary failure for diagnosis; CAMX-108 adds recovery for a surviving pending row. TIFF orientation
metadata derives from shutter context; RAW pixels are not rotated. Storage/encoding failure cannot
change route trust or failover. This one-shot path is the future `SensorDngWriter` input boundary; it
does not authorize a computational DNG writer to copy sensor metadata onto reconstructed samples.
Only structural RAW profile rejection may try an unattempted sibling under the exact same canonical
fingerprint.
