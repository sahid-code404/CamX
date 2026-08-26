# ADR-012: Acquisition-independent representation-typed processing

Status: Accepted

## Context

Calibration, alignment, demosaic, denoise, super-resolution, uncertainty, SIMD, and Vulkan will evolve
faster than Camera2 lifecycle and must not contaminate its ownership model. The original foundation
`RawFrame`/`ImageProcessor` seam proved one-time transfer but was not rich enough to distinguish
sensor-domain, camera-processed, opaque, CFA, and linear representations or to prove bounded resources.
Artistic rendering is not part of computational-negative production.

## Decision

Acquisition produces an immutable, generation-bound, representation-typed handoff. Non-copyable
owners transfer source leases exactly once into a typed immutable DAG. Every node/edge declares the
representation, dimensions, active area, precision, photometric and calibration domains, capture and
temporal identity, uncertainty, memory/lifetime, algorithm and parameter versions, backend legality,
workspace, tile/halo, temporal window, fallback, and semantic changes needed for compilation.

The graph compiler rejects a plan before capture when its resource proof cannot be satisfied. The
session owner knows only bounded acquisition handoff, cancellation, and release/durable-spool
completion; it never knows processor implementation and never waits for reconstruction to restore
preview. The complete representation and product model is authoritative in
[`COMPUTATIONAL_RAW_ARCHITECTURE.md`](../COMPUTATIONAL_RAW_ARCHITECTURE.md).

## Consequences

Later milestones may add deterministic scalar reference nodes and differentially qualified SIMD,
Vulkan, or AI providers behind bounded queues and CPU fallback. Sensor mode bypasses sample-changing
nodes. Processed-source output remains a processed-source master. Any need to change camera ownership,
representation truth, or the frozen graph semantics requires an explicit Tier-A architecture migration
rather than an implementation ticket.
