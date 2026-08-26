# ADR-018: Separate sensor and computational DNG contracts

Status: Accepted

## Context

A single-frame public sensor representation and a reconstructed negative have different sample and
metadata authority. Forcing both through one writer encourages copying a source result onto output
whose dimensions, geometry, color, noise, and uncertainty have changed.
The complete output model is defined in the
[Computational RAW Architecture](../COMPUTATIONAL_RAW_ARCHITECTURE.md).

## Decision

`SensorDngWriter` and `ComputationalDngWriter` are separate architectural contracts. Neither owns or
opens a camera, queries current UI or topology state, or fabricates metadata.

`SensorDngWriter` accepts one `SensorNegative` backed by interpretable sensor-domain evidence. It
writes a truthful single-capture sensor DNG with no CamX sample-changing reconstruction. Android
`DngCreator` may implement this contract only for profiles whose input and matched metadata make its
output truthful and validated.

`ComputationalDngWriter` accepts one completed `ComputationalNegative`. Its metadata authority is the
reconstructed output semantics, not a blind copy of one input frame. `FusedCfaRadiance` may use CFA
DNG semantics only while it remains a genuine CFA grid. Demosaiced, full-color, super-resolved, or
geometry-reconstructed `LinearSceneRgb` uses Linear DNG semantics. It is never remosaiced to look like
Bayer data.

YUV, P010, and other `CameraProcessed` input cannot be written as Sensor or sensor-sourced
Computational RAW. When DNG cannot truthfully represent a computational product, CamX emits a
truthful non-DNG computational master or reports typed unsupported output. It does not invent tags to
satisfy a decoder.

Both contracts use staged write, reopen, semantic validation, digest verification, and commit. The
exact `ComputationalDngWriter` implementation remains provisional until its prototype and
interoperability gates pass.

## Consequences

Sensor-DNG compatibility can be certified independently of computational DNG. A platform writer,
standards writer, or SDK can be replaced behind the appropriate contract without changing acquisition
or reconstruction semantics. DNG validity and real decoder interoperability remain separate evidence.
