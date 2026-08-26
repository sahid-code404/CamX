# ADR-017: Reference algorithms, qualified backends, and the AI boundary

Status: Accepted

## Context

SIMD, GPU, learned, and future accelerator implementations can improve cost, but treating a backend
as the algorithm makes correctness dependent on a device family or driver. Learned priors add a
second risk: plausible output can hide weak evidence, clipping, or calibration failure.
The complete provider and AI contract is defined in the
[Computational RAW Architecture](../COMPUTATIONAL_RAW_ARCHITECTURE.md).

## Decision

Algorithm semantics and execution backends are separate contracts. Every required algorithm has a
deterministic scalar CPU reference implementation. SIMD, Vulkan, AI, and future accelerator providers
must pass differential numerical, representation, uncertainty, resource, cancellation, and failure
tests against the reference before a capability profile may select them.

Provider selection uses runtime capability and measured evidence. Manufacturer, device model, SoC,
GPU marketing name, sensor name, numeric camera ID, hard-coded focal role, and resolution are not
execution policy. Provider failure or fallback cannot change source truth, graph semantics, or the
declared product representation.

A future learned node may estimate scene state, propose capture choices inside deterministic bounds,
refine noise, alignment or visibility, provide a reconstruction prior, detect artifacts, or estimate
quality and out-of-distribution state. It may not alter Sensor-mode samples, fabricate calibration,
change canonical camera identity, hide clipping or occlusion, relabel processed input, bypass
resource limits, or become the only correctness path for required functionality.

Every learned node records its model identity and hash, version, runtime/backend, precision,
confidence or out-of-distribution state, fallback, and whether a learned prior affected pixels.

## Consequences

The API-23 scalar path remains the correctness baseline. Optimized and learned providers are optional,
quarantinable accelerators whose removal cannot invalidate the architecture or existing products.
Their support claims require differential and physical evidence rather than brand routing.
