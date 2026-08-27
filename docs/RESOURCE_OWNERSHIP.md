# Resource Ownership

There is exactly one authoritative owner at any instant. A transfer is explicit in a method contract;
sharing a reference does not share close authority. Callback delivery is not ownership acceptance:
the exact one-shot operation permit must be consumed before a delivered resource can be adopted.

| Resource | Authoritative owner | Created | Destroyed | May transfer? | Stale callback action |
|---|---|---|---|---|---|
| `CameraDevice` | `CameraSessionController` | exact-permit-valid `onOpened` adoption | switch, pause, fatal session error, owner close | no | `CloseOnceCameraResource` detaches one cleanup under admission; close once after unlock |
| `CameraCaptureSession` | `CameraSessionController` | exact-permit-valid `onConfigured` adoption | reconfigure, switch, pause, owner close | no | `CloseOnceCameraResource` detaches one cleanup under admission; close once after unlock |
| Pending callback permit | `CameraAsyncOwnership` | authoritative intent is published before an async command | consumed once or invalidated by replacement/pause/shutdown | no | stale/duplicate permit cannot publish or adopt |
| Detached cleanup plan/permit | `CameraSessionController` | resources are detached after intent/generation invalidation | consumed once after all closes are attempted | no | stale cleanup completion cannot publish a destination |
| Stable preview View | Compose/Activity view tree | camera screen creation | Activity destruction | no | ignore callback for old view identity |
| Preview `Surface` lease | `PreviewSurfaceOwner` until attached; session lease while active | Surface callback | surface destroy or session cleanup | lease only | release stale lease, never current lease |
| RAW transaction token | `CameraSessionController` | verified shutter admission and bounded reservation | terminal cleanup or lifecycle/switch revocation | no | exact token/generations required; otherwise ignore and close delivered resources |
| Temporary RAW `ImageReader` | controller transaction through `AndroidCameraOwnerPlatform` | `CONFIGURING_RAW`, `maxImages = 2` | before preview-only restoration or on cancellation | no | detach listener, close reader; delivered stale image closes separately |
| Temporary RAW surface/binding | RAW reader, leased by token-bound output plan/session | reader creation/session configuration | reader/session teardown | lease only | never attach to a newer token; reader close releases it |
| Temporary RAW capture session | `CameraSessionController` | exact-permit-valid RAW `onConfigured` adoption | terminal cleanup before preview-only session, or cancellation | no | stale delivery receives one detached close authority |
| RAW `Image` | timestamp pairer, then `SensorDngWriter` | exact-permit-valid callback acquire | orphan/overflow/stale/timeout or writer completion | once, pairer to writer | close immediately and exactly once |
| Capture metadata/result join entry | bounded timestamp pairer | exact-permit-valid capture callback | pair transfer, timeout, cancellation, or overflow | immutable reference only | discard; never pair heuristically |
| Sensor DNG writer | one transaction I/O job | exact image/result pair transfer | saved/failed/cancelled outcome after image close | owns paired image once | revoked publish claim prevents commit; writer still closes image |
| DNG output stream | `AndroidSensorDngWriter` | pending row successfully opens | DNG write flush/close before reopen validation | no | close in `finally`; transaction deletes pre-commit row |
| Immutable acquisition handoff (future) | acquisition transaction, then one imaging job after explicit move | exact current permit plus pre-admitted destination | source lease release or durable spool commit | once only | reject handoff and release only its source lease |
| Imaging job (future) | one in-process `ImagingJobCoordinator` | compiled graph and complete resource reservation | verified commit, cancellation, or typed failure cleanup | no | stale job generation cannot publish |
| Computational negative (future) | imaging job, then exactly one output transaction after explicit move | graph completion with representation proof | committed artifact or failed-output cleanup | once only | stale output transaction cannot publish |
| Encoded RAW-video payload (future) | codec lease, then container writer after explicit move | bounded reversible encode or `PACKED_NONE` | durable frame/group acceptance or release on failure | once only | release; record no frame commit |
| RAW-video active segment (future) | one container-writer transaction | recording admission and segment open | durable seal or recoverable incomplete-tail closure | no | stale writer cannot advance manifest/checkpoint |
| Computational DNG transaction (future) | `ComputationalDngWriter` output transaction | completed computational negative plus output-derived metadata | reopen/semantic/digest validation and publish, or cleanup | no | stale transaction cannot publish |
| Pending MediaStore row | `MediaStoreTransaction` | API-gated pending insert | publish only after reopen validation and atomic claim; otherwise delete | no | delete if transaction owns it; retain bounded recovery identity when deletion fails |
| `AImage` (future optional) | API-24 Tier-A media-image module | only after API/library/symbol capability succeeds | optional owner destructor | explicit move only | unavailable/unsupported leaves Java ownership authoritative |
| `AHardwareBuffer` (future optional) | API-26 Tier-A hardware-buffer module | only after `libnativewindow.so` and symbol capability succeeds | optional owner destructor | move only unless an explicit acquire creates another reference | unavailable/unsupported creates no native owner |
| API-23 native core | `NativeCore` load boundary and native RAII types | process loads `libcamx_core.so` | process unload; bounded leases destruct normally | coarse JNI values/handles only | load failure is typed native unavailability, not a camera-route failure |
| Native buffer | `NativeBufferPool` lease | bounded pool checkout | lease return/pool shutdown | move-only lease | return lease |
| Topology snapshot | `CameraTopologyRepository` | pure resolver output | GC after atomic replacement/readers release | immutable sharing | do not publish stale reconciliation |
| Hot/full cache write | `CameraCacheRepository` | persistence request | atomic replace or temp cleanup | no | discard stale write before replace |
| Settings snapshot | `SettingsRepository` | update | GC after atomic replacement | immutable sharing | version-check persistence completion |
| OTA download `.part` | `UpdateRepository` transaction | user starts download | rename after verification or delete on failure/cancel | no | delete stale part |
| OTA state | `UpdateRepository` | post-frame/manual check | repository lifecycle | immutable sharing | do not publish stale request |
| Camera callback thread | `CameraSessionController` | owner construction | after permit invalidation and all detached Camera2 resources close | no | callbacks require exact permit; generation equality alone cannot admit them |
| CPU/native worker pool (future) | one in-process imaging runtime | lazy after graph admission | runtime shutdown/background memory trim after jobs cancel/checkpoint | no | discard queued stale job token |
| GPU submission actor (future) | one in-process imaging runtime | lazy only after qualified backend selection | device loss, runtime shutdown, or background trim after fence cleanup | no | discard stale job submission; never mutate camera state |

Debug `CameraResourceSnapshot` reads counters owned by each boundary. It is diagnostic observation,
not ownership transfer. `CloseOnceCameraResource` resolves each callback delivery once as adopted or
stale-detached. The first stale resolution receives one `CameraResourceCleanup`; duplicate callbacks
and already-adopted deliveries receive no cleanup authority. Pause, switch, and shutdown invalidate
admission and detach current resources under the non-suspending mutation gate; close calls run outside
it. `CameraCleanupPlan` attempts every detached close once, retains later failures as suppressed detail,
and only the current cleanup permit may publish completion.

Future computational ownership entries are frozen contracts, not CAMX-108 implementation. Their full
representation, source-retention, codec/container, and execution rules are authoritative in
[`COMPUTATIONAL_RAW_ARCHITECTURE.md`](COMPUTATIONAL_RAW_ARCHITECTURE.md).
