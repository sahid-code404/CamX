# ADR-016: Typed processing graph and pre-execution resource proof

Status: Accepted

## Context

High-resolution burst and video processing can exhaust memory or miss sustained deadlines if a graph
is treated as an untyped list of operations and resource feasibility is discovered by allocation
failure. Correctness also depends on representation, geometry, calibration, time, and uncertainty,
not only buffer element type.
The complete graph and resource contract is defined in the
[Computational RAW Architecture](../COMPUTATIONAL_RAW_ARCHITECTURE.md).

## Decision

Processing is an immutable typed DAG. Each edge or node declares the relevant representation,
dimensions, active area, precision, photometric domain, calibration identity, capture and temporal
identity, uncertainty semantics, memory domain, lifetime, algorithm and parameter-schema versions,
legal backends, workspace formula, latency class, tile and halo, temporal lookback and lookahead,
fallback, and whether it changes samples, geometry, or representation.

A graph compiler validates semantic compatibility and calculates an overflow-safe execution plan
before acquisition admission. The plan reserves bounded source slots, metadata, queue entries,
temporal state, native pools, graph workspace, writer state, and output capacity. A graph that cannot
prove its bounds is rejected before capture. High-resolution work is tiled or incremental; unbounded
full-frame stacks and OOM-as-capability-probing are forbidden.

Every queue has a capacity and explicit overflow policy. Cancellation is cooperative at declared
node, tile, and transaction boundaries and does not transfer ownership. RAW video cannot silently or
arbitrarily drop frames: source gaps are recorded, and runtime overload causes a predeclared
representation epoch, source-only fallback where authorized, or a controlled stop.

Camera mutation and callback paths perform no graph compilation, compression, disk I/O, or heavy
pixel work. Acquisition starts only after the applicable resource plan is admitted.

## Consequences

Feasibility becomes a deterministic plan property instead of a device crash experiment. Nodes and
backends can be replaced without weakening ownership or memory bounds, and rejected plans leave the
camera topology and trust state unchanged.
