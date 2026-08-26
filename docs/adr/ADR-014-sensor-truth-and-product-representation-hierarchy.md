# ADR-014: Sensor truth and product representation hierarchy

Status: Accepted

## Context

Android exposes several camera representations, but a public RAW format does not prove that no
binning, remosaic, defect correction, black-level handling, shading correction, sensor correction,
or HAL correction occurred before the application received it. Camera-processed data and opaque
transport also cannot acquire sensor meaning merely because later processing is lossless.

Without structural distinctions, fallback, reconstruction, and serialization can silently relabel
YUV, P010, or guessed private data as RAW. The authoritative terminology and complete model are in
the [Computational RAW Architecture](../COMPUTATIONAL_RAW_ARCHITECTURE.md).

## Decision

Acquisition classifies evidence into an explicit hierarchy equivalent to:

```text
AcquiredRepresentation
|- InterpretableSensorDomain
|- CameraProcessed
`- OpaqueTransport
```

`InterpretableSensorDomain` means a public interpretable sensor-domain representation with no CamX
sample-changing processing. It does not claim perfect untouched sensor data. `CameraProcessed`
retains its processed-source identity. `OpaqueTransport` is preserved opaquely or rejected; CamX
does not decode `RAW_PRIVATE` or another private representation by guess.

Products preserve source and pixel semantics structurally:

- `SensorNegative<InterpretableSensorDomain>` contains one interpretable sensor-domain capture and no
  CamX sample-changing reconstruction.
- `ComputationalNegative<InterpretableSensorDomain, ComputationalPixelRepresentation>` contains a
  reconstructed sensor-sourced product.
- `ProcessedSourceMaster<CameraProcessed, ProcessedPixelRepresentation>` remains camera-processed
  even if a scientific graph later transforms it.

`ComputationalNegative` has two legal pixel meanings. `FusedCfaRadiance` requires a genuine CFA
output grid. Demosaic, super-resolution, geometry reconstruction, or any full-color reconstruction
produces `LinearSceneRgb`. CamX never remosaics `LinearSceneRgb` merely to obtain Bayer-looking data.
YUV and P010 can never become `SensorNegative` or a sensor-sourced `ComputationalNegative`.

Lossless unpacking, padding removal, byte-order normalization, and storage repacking do not by
themselves change samples. Every product and serialized artifact retains its exact representation,
capture identity, and source provenance.

## Consequences

Mode routing, graph compilation, DNG writing, video serialization, fallback, and UI claims must
consume these types rather than infer truth from a filename or pixel layout. Processed fallback can
remain useful without masquerading as RAW, and unsupported or opaque evidence fails honestly.
