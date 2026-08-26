# ADR-021: Bounded in-process V1 computational execution

Status: Accepted

## Context

Scientific image processing needs parallel CPU work, optional GPU serialization, native memory
pools, cancellation, and failure cleanup. A service or separate process does not create resource
bounds by itself and would add lifecycle and IPC semantics before V1 has measured workloads.
The complete execution contract is defined in the
[Computational RAW Architecture](../COMPUTATIONAL_RAW_ARCHITECTURE.md).

## Decision

V1 computational imaging executes in the foreground application process under one lifecycle-scoped
job owner. It uses a fixed-capacity CPU/native worker pool, bounded native buffer pools, bounded
queues, explicit job scope and cancellation, and at most one actor that owns GPU submission order,
fences, and context recovery. Admission reserves its graph and writer resources before capture.

`CameraSessionController` remains the sole Camera2 device, session, output, and callback authority.
Processing receives only an immutable generation-bound acquisition handoff. It cannot open a camera,
configure a session, reconstruct identity from current UI state, change the active lens or topology,
or perform discovery. Camera mutation and callback paths never wait for or perform scientific work.

No `Service`, `ForegroundService`, `JobService`, or `WorkManager` exists merely to host compute.
Backgrounding cancels or checkpoints according to the admitted source-durability policy; V1 does not
promise background completion.

Worker, allocation, codec, writer, and recoverable GPU failures become typed job failures and cannot
damage camera trust. In-process workers cannot contain a fatal native signal or memory corruption;
V1 documentation and certification must not claim otherwise.

A separate restartable compute process is a provisional future Tier-A option. It requires its own ADR
and prototype for API-23 IPC, bounded schemas and descriptor transfer, ownership, lifecycle, process
death, idempotent recovery, memory duplication, GPU recreation, and continued exclusion of Camera2.

## Consequences

V1 has one observable ownership and resource model consistent with
[ADR-001](ADR-001-single-camera-device-owner.md),
[ADR-010](ADR-010-no-background-service.md),
[ADR-012](ADR-012-future-processing-boundary.md), and
[ADR-013](ADR-013-api-23-platform-and-async-ownership.md). Durable sources can permit later foreground
resume, but process isolation and background execution remain separate future product and
architecture decisions.
