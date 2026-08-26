# ADR-015: Shared evidence-constrained photo and video imaging engine

Status: Accepted

## Context

Separate photo and video reconstruction stacks would duplicate calibration, alignment, noise,
uncertainty, and provenance rules and would allow the same evidence to acquire different scientific
meaning. Fixed frame recipes and hidden fallback also turn device assumptions into product claims.
The complete engine and product model is defined in the
[Computational RAW Architecture](../COMPUTATIONAL_RAW_ARCHITECTURE.md).

## Decision

Photo and video use one `EvidenceConstrainedImagingEngine`. They share representation, measurement,
calibration, noise, alignment, visibility, occlusion, motion, reconstruction, uncertainty, manifest,
and algorithm-version contracts. A mode changes capture and temporal policy, not scientific truth.

The semantic user intent has four independent axes:

- `ReconstructionIntent`: `SOURCE_PRESERVING` or `COMPUTATIONAL`.
- `SourcePolicy`: `SENSOR_DOMAIN_REQUIRED` or `BEST_PUBLIC_SOURCE`.
- `VideoExecution`: `MAXIMUM_QUALITY_DEFERRED` or `REALTIME_CAUSAL`.
- `SourceRetention`: `KEEP_SOURCE` or `DELETE_AFTER_VERIFIED_OUTPUT`.

`SOURCE_PRESERVING` bypasses every CamX sample-changing reconstruction node. A computational request
uses a bounded adaptive `CaptureRecipe` derived from runtime capability, measurement, motion,
exposure, resource, and cancellation evidence; it does not encode a universal fixed frame count.
Reconstruction uses calibrated noise plus explicit alignment, visibility, occlusion, motion, and
uncertainty. Failure to establish support causes conservative fallback or typed refusal rather than
invented evidence.

One reconstruction uses one canonical optical lens. CamX does not hide lens switching or lens fusion
inside this engine. `BEST_PUBLIC_SOURCE` may admit a camera-processed source only when the resulting
product retains `ProcessedSourceMaster` truth. `SENSOR_DOMAIN_REQUIRED` fails rather than making that
substitution.

Computational-negative production stops before tone mapping, beautification, skin smoothing,
artistic contrast, saturation styling, output sharpening, look LUTs, or social-media rendering.

## Consequences

Photo and video features can evolve through recipes and temporal policies without creating parallel
scientific engines. Sensor mode remains an auditable bypass. Rendering is a separate future
architecture, and multi-lens reconstruction requires a later explicit architecture decision.
