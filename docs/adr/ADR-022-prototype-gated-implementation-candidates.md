# ADR-022: Prototype-gated computational RAW implementation candidates

Status: Proposed

## Context

The Revision 2 semantic architecture is frozen, but several plausible implementation technologies do
not yet have the Android corpus, durability, performance, interoperability, API-floor, or physical-
device evidence required for acceptance. Mentioning a leading candidate must not silently promote it
to a permanent dependency.

## Proposed decision

Keep the following selections provisional behind the milestones and gates in the
[Computational RAW Architecture](../COMPUTATIONAL_RAW_ARCHITECTURE.md):

- MCAP-based CXRB remains the leading container candidate until M2A proves sustained throughput,
  bounded memory and indexes, CPU/energy cost, sealing, truncation and power-loss recovery, corruption
  isolation, random access, schema evolution, files larger than 4 GB, and long-soak behavior.
- JPEG-LS, CFA-split JPEG-LS, predictor plus Rice, predictor plus Zstd, LZ4, and other reversible
  codecs remain M2B candidates. `PACKED_NONE`, accepted by ADR-019, remains the mandatory baseline.
- The exact `ComputationalDngWriter` implementation remains an M8B prototype and interoperability
  decision. `SensorDngWriter` and computational DNG semantics remain accepted independently.
- Direct `AHardwareBuffer` or Vulkan ingest, advanced precision and storage layouts, tile and segment
  dimensions, and optimized providers remain prototype- and capability-gated.
- A separate restartable compute process remains deferred until a dedicated future Tier-A ADR and
  prototype prove its IPC, ownership, lifecycle, death, recovery, memory, GPU, and API-23 behavior.

Each candidate is implemented only behind its frozen adapter. Failure discards or disables that
candidate without changing source/product types, camera ownership, acquisition, graph semantics,
reference algorithms, or reconstruction.

## Consequences

This ADR records candidate boundaries and required evidence; it selects no container, compressed
codec, computational DNG library, direct-ingest path, precision layout, optimized provider, or worker
process. A later accepted ADR must identify the measured winner, supported profiles, fallback, and
rollback after the corresponding gate passes.
