# Error Model

Every `CameraFailure` has a category, permanence, trust effect, retry permission, same-canonical
failover permission, and user-action requirement. Error text is presentation data and never drives
policy.

| Failure | Category | Structural? | Trust effect | Auto retry | Same-canonical failover | User action |
|---|---|---:|---|---:|---:|---|
| `PermissionDenied` | permission | no | none | no | no | grant permission/settings if permanently denied |
| `CameraInUse` | availability | no | temporarily unavailable | bounded | no | close competing app if persistent |
| `MaximumCamerasInUse` | availability | no | temporarily unavailable | bounded | no | optional |
| `CameraDisabled` | policy | no | temporarily unavailable | no | no | device/admin policy |
| `CameraDisconnected` | device | no | temporarily unavailable | bounded | no | none |
| `CameraDeviceError` | device | depends on platform reason | temporary by default | bounded | only if classified structural | optional |
| `OpenTimeout` | timing | no | temporary | bounded | no | none |
| `SurfaceUnavailable` | surface | no | none | when surface returns | no | none |
| `SessionConfigurationRejected` | profile/session | yes | preview structurally rejected | no | yes | none |
| `UnsupportedStreamCombination` | profile/session | yes | preview or RAW structural | no | yes | none |
| `FpsRangeRejected` | preview policy | yes for configuration, not lens | config evidence only | retry without override | no | adjust setting |
| `PreviewTimeout` | timing | no by default | temporary | bounded | no | none |
| `RawUnsupported` | RAW | yes | RAW rejected only | no | yes | none |
| `RawSessionRejected` | RAW | yes | RAW rejected only | no | yes | none |
| `RawCaptureTimeout` | RAW timing | no | temporary RAW | bounded | no | none |
| `RawPairTimeout` | RAW pairing | no | none | bounded | no | none |
| `DngWriteFailure` | encoding/storage | no | none | no | no | retry/save diagnostics |
| `MediaStoreFailure` | storage | no | none | no | no | free storage/permission |
| `StaleSelection` | concurrency | no | none | no | no | none |
| `StaleSession` | concurrency | no | none | no | no | none |
| `StaleCapture` | concurrency | no | none | no | no | none |
| `Cancelled` | control | no | none | no | no | none |

Classification is centralized and exhaustive. A transient error is never persisted as permanent
rejection. An output error is never converted to a camera-route error. Retry budgets are bounded and
reset only by explicit success or lifecycle policy; they are not loops hidden inside callbacks.
