# ADR-019: RAW-video container and codec abstraction

Status: Accepted

## Context

A container or compression library is replaceable implementation machinery, while representation,
provenance, durability, and reversibility are product contracts. Selecting a format before sustained
Android evidence would couple camera and reconstruction architecture to an unproven candidate.
The complete video persistence contract and prototype gates are defined in the
[Computational RAW Architecture](../COMPUTATIONAL_RAW_ARCHITECTURE.md).

## Decision

CamX freezes separate `RawVideoContainerContract` and `RawVideoCodecContract` boundaries. Neither
contract changes `SensorFrame`, `ComputationalNegative`, `ProcessedSourceMaster`, acquisition,
processing graphs, or photo/video reconstruction.

The container contract carries bounded, versioned frame ordinals; timestamps and timebase; canonical
lens/profile/route identity; representation, dimensions, active area, CFA, precision, and calibration;
the required Camera2 metadata subset; source/output hashes; graph, algorithm, model, and codec
versions; confidence and uncertainty; gyro/OIS evidence; representation and codec epochs; explicit
gaps; independently recoverable units; durable checkpoints; 64-bit offsets; bounded indexes and
parser allocations; schema evolution; unknown-required-field fail-closed behavior; and a final
manifest binding.

The contract requires sequential bounded append plus defined crash, truncation, power-loss,
corruption, random-access, and large-file behavior. MCAP-based CXRB is only the leading M2A candidate.
This ADR does not select it.

`PACKED_NONE` is the mandatory production and admission-safe codec baseline. It serializes canonical
meaningful raster bytes, not undefined Android allocator padding. Admission reserves its worst case
and never relies on an expected compression ratio.

Compressed codecs, including JPEG-LS, CFA-split JPEG-LS, predictor plus Rice, predictor plus Zstd,
and LZ4 variants, remain optional M2B candidates. A candidate must be reversible, versioned, bounded,
fuzzable, replaceable, and independently decodable at its declared granularity. It declares workspace
and output bounds, integrity data, fallback, and corruption scope. No compressed winner is accepted
by this ADR.

## Consequences

M2A may reject MCAP/CXRB and M2B may reject every compressed codec without reopening source,
acquisition, graph, or reconstruction decisions. `PACKED_NONE` remains a valid production outcome,
and storage or codec failure cannot alter camera trust.
