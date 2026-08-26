# ADR-020: Exact-profile certification, manifest, and source retention

Status: Accepted

## Context

A build, advertised capability, or successful capture on one route does not prove sustained or
interoperable behavior on another. Computational products also become unauditable if their sources,
algorithms, fallbacks, or deletion transaction cannot be reconstructed from durable evidence.
The complete evidence and retention model is defined in the
[Computational RAW Architecture](../COMPUTATIONAL_RAW_ARCHITECTURE.md).

## Decision

Support is certified for an exact evidence profile, including canonical lens, route/profile, sensor
pixel mode, source format, size, FPS/exposure policy, stream combination, applicable dynamic-range
and color configuration, stabilization, provider/OS environment, storage target class, and
algorithm/container/codec version.

Certification uses explicit `DISCOVERED`, `ADVERTISED`, `CONFIGURATION_VERIFIED`, `FRAME_VERIFIED`,
`SENSOR_PHOTO_CERTIFIED`, `COMPUTATIONAL_PHOTO_CERTIFIED`, `SENSOR_VIDEO_SUSTAINED`,
`COMPUTATIONAL_VIDEO_OFFLINE_CERTIFIED`, `COMPUTATIONAL_VIDEO_REALTIME_CERTIFIED`, and
`INTEROPERABILITY_CERTIFIED` states. Photo, video, and interoperability are distinct evidence lanes;
an unrelated lane is not an artificial prerequisite, and no state implies another without its own
recorded proof. CI, emulator, and physical-device results remain separate.

Every source and product is bound to a compact versioned processing manifest sufficient to identify
its representation, acquisition identity, `CaptureRecipe`, contributing frames, calibration,
algorithm graph and versions, parameters, backend and precision, models, fallbacks, source hashes,
and output hash. Dense diagnostic maps may remain optional external artifacts, but their absence may
not make the core product ambiguous.

Source retention is an explicit transaction: `KEEP_SOURCE` or `DELETE_AFTER_VERIFIED_OUTPUT`.
Deletion may occur only after the output write completes, reopen succeeds, semantic validation and
digest verification succeed, and the transaction commits. The product must disclose that deleting
the source destroys future complete reproducibility even though its remaining manifest can still
support audit.

Runtime capability and exact evidence drive certification. Marketing identity, manufacturer, model,
SoC, GPU name, sensor name, camera-ID numbering, focal-role assumptions, and resolution-based optical
identity do not.

## Consequences

Claims are narrow, versioned, and revocable when an environment or implementation changes. A green
CI run cannot become a physical-camera claim, source deletion cannot precede output proof, and every
artifact remains traceable to its precise evidence and processing history.
