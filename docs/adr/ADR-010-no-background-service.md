# ADR-010: No background service

Status: Accepted

## Context

Ordinary camera, development update, and V1 computational work is foreground and lifecycle-bound.
Services add process/lifecycle competition and can delay camera startup. Process isolation and
background execution are separate decisions: assigning compute to another process would not by itself
grant durable background execution.

## Decision

Do not use `Service`, `ForegroundService`, `JobService`, `WorkManager`, or a background component for
normal camera, dev OTA, or V1 computational behavior. Use bounded in-process lifecycle execution after
the relevant admission gate. Deferred computational work may checkpoint durable sources and resume
only when an authorized foreground runtime returns.

## Consequences

Checks, downloads, and compute cancel or checkpoint with process/lifecycle and may resume only by
explicit repository policy. A future separate compute process and any future background-execution
component each require a dedicated Tier-A ADR and prototype; neither may inherit camera ownership.

The complete V1 execution contract is frozen in
[`COMPUTATIONAL_RAW_ARCHITECTURE.md`](../COMPUTATIONAL_RAW_ARCHITECTURE.md).
