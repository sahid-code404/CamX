# Threading Model

CamX uses four execution domains and no implicit global work.

| Domain | Owner | Permitted work | Forbidden work |
|---|---|---|---|
| Main | Android/Compose | Activity lifecycle, permission, stable view ownership, low-frequency UI state | Camera callbacks, discovery, file/network I/O, per-frame formatting |
| Camera control | `CameraSessionController` | All CameraDevice/session calls and callbacks, state transitions, generation checks | DataStore, network, DNG I/O, CPU image processing |
| I/O | repositories/transactions | Cache/settings atomic persistence, MediaStore writes, post-frame OTA | CameraDevice/session mutation, UI rendering |
| Native workers | native processing runtime | Bounded future frame processing and native diagnostics | Camera control plane, unbounded submission, first-frame initialization |

The camera dispatcher is one long-lived component created and destroyed with the app camera graph,
not per lens or operation. A single mutex/actor serializes open, switch, close, pause, resume, surface
replacement, preview reconfiguration, RAW session configuration, capture, and preview restoration.
Callback code carries its generation and performs a constant-time staleness check before publishing.

Cancellation is cooperative at transaction boundaries. Cancellation does not transfer resource
ownership: the current owner closes its lease in `finally`. No `GlobalScope`, `runBlocking`,
`Thread.sleep`, busy wait, polling loop, thread-per-frame, or unbounded executor is permitted.

Immutable snapshots are published through `StateFlow` or `AtomicReference`. Ordinary readers never
lock. Mutable collections remain confined to one dispatcher or protected by a small local lock and
are never exposed.
