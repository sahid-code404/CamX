# Threading Model

CamX uses explicit execution domains and no implicit global work. The imaging coordinator, CPU pool,
and GPU actor below are frozen future boundaries; CAMX-108 does not implement them.

| Domain | Owner | Permitted work | Forbidden work |
|---|---|---|---|
| Main | Android/Compose | Activity lifecycle, permission, stable view ownership, low-frequency UI state | Camera callbacks, discovery, file/network I/O, per-frame formatting |
| Camera control | `CameraSessionController` | CameraDevice/session calls, callback admission, state transitions, permit consumption, generation checks | DataStore, network, DNG I/O, CPU image processing, awaiting work while the mutation gate is held |
| I/O | repositories/transactions | Cache/settings atomic persistence, MediaStore writes, post-frame OTA | CameraDevice/session mutation, UI rendering |
| Imaging coordination | one in-process job coordinator | admission, typed DAG scheduling, cancellation, reservation and commit state | Camera control, unbounded job concurrency, per-pixel work |
| CPU/native workers | one bounded process pool | reference/qualified node execution and native diagnostics | Camera control plane, thread-per-frame, unbounded submission, first-frame initialization |
| GPU submission | one in-process GPU actor | qualified command submission, fences, context and device-loss handling | Camera control, multiple implicit owners, algorithm-semantic changes |

The camera dispatcher is one long-lived component created and destroyed with the app camera graph,
not per lens or operation. `CameraStateMutationGate` serializes open, switch, close, pause, resume,
surface replacement, preview reconfiguration, RAW session configuration, capture, and preview
restoration. Its externally callable adapter may suspend only to dispatch and acquire serialization;
the mutation block itself is non-suspending, short, and performs no timeout, join, deferred wait,
platform callback wait, I/O, or resource close.

Before a future asynchronous Camera2 command is issued, the mutation block publishes immutable intent
and creates a single-use `PendingCameraOperationPermit`. A callback first re-enters one non-suspending
mutation, consumes the exact permit, and only then may publish state or adopt its delivered resource.
Launching unrelated coroutine work from a callback cannot stand in for admission. A generation-only
or route-only check is insufficient, and duplicate callbacks are stale after first consumption.

Pause, switching, and shutdown invalidate permits and detach owned resources inside the mutation.
Potentially re-entrant or blocking `close()` calls execute outside it. Completion re-enters through a
one-shot cleanup permit, so old cleanup cannot overwrite newer intent. Detached resources are closed
exactly once; cleanup continues after one close throws and retains later failures as suppressed detail.

Cancellation is cooperative at transaction boundaries. Cancellation does not transfer resource
ownership: the current owner invalidates admission before closing its detached lease. No `GlobalScope`,
`runBlocking`, `Thread.sleep`, busy wait, polling loop, thread-per-frame, or unbounded executor is
permitted.

Computational execution consumes an immutable acquisition handoff and uses a job generation distinct
from camera selection/session generations. Preview restoration waits only for acquisition resources to
be released or durably spooled, never for reconstruction to finish. V1 work is lifecycle-scoped and
in-process; backgrounding cancels or checkpoints according to source durability. No service or work
scheduler is introduced merely to host compute. See
[`COMPUTATIONAL_RAW_ARCHITECTURE.md`](COMPUTATIONAL_RAW_ARCHITECTURE.md).

Immutable snapshots are published through `StateFlow` or `AtomicReference`. Ordinary readers never
lock. Mutable collections remain confined to one dispatcher or protected by a small local lock and
are never exposed.
