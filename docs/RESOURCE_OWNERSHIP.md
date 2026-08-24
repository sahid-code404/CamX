# Resource Ownership

There is exactly one authoritative owner at any instant. A transfer is explicit in a method contract;
sharing a reference does not share close authority.

| Resource | Authoritative owner | Created | Destroyed | May transfer? | Stale callback action |
|---|---|---|---|---|---|
| `CameraDevice` | `CameraSessionController` | generation-valid `onOpened` | switch, pause, fatal session error, owner close | no | close the device delivered to stale callback |
| `CameraCaptureSession` | `CameraSessionController` | generation-valid `onConfigured` | reconfigure, switch, pause, owner close | no | close the delivered session |
| Stable preview View | Compose/Activity view tree | camera screen creation | Activity destruction | no | ignore callback for old view identity |
| Preview `Surface` lease | `PreviewSurfaceOwner` until attached; session lease while active | Surface callback | surface destroy or session cleanup | lease only | release stale lease, never current lease |
| RAW `ImageReader` | `RawCaptureTransaction` under session owner | `CONFIGURING_RAW` | transaction `finally` before preview restored | no | close reader and drain/close images |
| RAW `Image` | timestamp pairer, then transaction/writer | image callback acquire | orphan/overflow/stale/timeout or writer completion | once, pairer to writer | close immediately |
| Capture metadata/result | `RawCaptureTransaction` | capture callback | pair/write completion or timeout | immutable reference only | discard |
| Pending MediaStore row | `MediaStoreTransaction` | insert with `IS_PENDING=1` | publish on success; attempt delete and report cleanup failure | no | delete if transaction owns it; CAMX-108 recovers a surviving row |
| `AImage` | `NativeImageOwner` | native reader callback/acquire | owner destructor | explicit move only | destroy owner |
| `AHardwareBuffer` | `HardwareBufferOwner` | acquire | final owner destructor | ref-counted owner move/copy contract | release stale owner |
| Native buffer | `NativeBufferPool` lease | bounded pool checkout | lease return/pool shutdown | move-only lease | return lease |
| Topology snapshot | `CameraTopologyRepository` | pure resolver output | GC after atomic replacement/readers release | immutable sharing | do not publish stale reconciliation |
| Hot/full cache write | `CameraCacheRepository` | persistence request | atomic replace or temp cleanup | no | discard stale write before replace |
| Settings snapshot | `SettingsRepository` | update | GC after atomic replacement | immutable sharing | version-check persistence completion |
| OTA download `.part` | `UpdateRepository` transaction | user starts download | rename after verification or delete on failure/cancel | no | delete stale part |
| OTA state | `UpdateRepository` | post-frame/manual check | repository lifecycle | immutable sharing | do not publish stale request |
| Camera callback thread | `CameraSessionController` | owner construction | after all Camera2 resources close | no | callbacks self-reject by generation |
| Native workers | native processing runtime | lazy post-frame initialization | runtime shutdown/background memory trim | no | discard queued stale token |

Debug `CameraResourceSnapshot` reads counters owned by each boundary. It is diagnostic observation,
not ownership transfer.
