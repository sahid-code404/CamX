# Camera State Machine

`CameraSessionController` is the sole state and Camera2 resource owner. Commands enter its serialized
camera-control context. Every transition emits a low-frequency immutable snapshot; frame callbacks
update bounded metrics, not state flow.

## States

| State | Resources that may be live | Meaning |
|---|---|---|
| `CLOSED` | none | Owner is shut down; only a new owner instance can restart it. |
| `WAITING_FOR_SURFACE` | stable surface reference may be absent | A route/lifecycle intent exists but no usable surface lease exists. |
| `OPENING` | open callback lease | One generation-bound device open is in flight. |
| `CONFIGURING_PREVIEW` | device + preview surface | A preview-only session is being configured. |
| `PREVIEWING` | device + preview session + display surface | Repeating display request is active. |
| `SWITCHING` | old leases closing; new open callback may follow | Explicit selection changed; selection and session generations advanced. |
| `CONFIGURING_RAW` | device + temporary RAW output + display surface as supported | Bounded capture transaction is creating a non-idle session. |
| `CAPTURING_RAW` | RAW session + one request + transaction | Exactly one RAW request is outstanding. |
| `PAIRING_RAW` | bounded unmatched image/result maps | Exact sensor timestamps are being paired. |
| `WRITING_DNG` | matched image/result + pending MediaStore row | Camera route remains unchanged; I/O failure is nonstructural. |
| `RESTORING_PREVIEW` | device + display surface; RAW resources closing | Temporary outputs are destroyed and preview-only session is rebuilt. |
| `PAUSING` | leases closing | Lifecycle prevents new work; generations have already advanced. |
| `RECOVERABLE_ERROR` | ideally none; selected route retained | Retry/user/environment may resolve a transient failure. |
| `STRUCTURAL_ERROR` | none except stable UI surface | Active profile was structurally rejected; same-canonical policy may choose a sibling. |

## Transition contract

All resources listed as destroyed are closed before the destination is published unless an Android
callback owns a late resource; that callback closes it after failing its generation guard.

| Caller / event | Allowed source | Destination | Create / destroy | Generation, cancellation, stale behavior | Failure class |
|---|---|---|---|---|---|
| Runtime `resume(route)` without surface | `WAITING_FOR_SURFACE`, `RECOVERABLE_ERROR` | `WAITING_FOR_SURFACE` | none | retain selection; cancellation has no effect | `SurfaceUnavailable` |
| Surface owner `attach` | `WAITING_FOR_SURFACE`, `RECOVERABLE_ERROR` | `OPENING` | create open callback lease | advance session; stale surface callback releases only its lease | open failures below |
| Runtime `open(route)` | `WAITING_FOR_SURFACE`, `RECOVERABLE_ERROR`, initial state | `OPENING` or `WAITING_FOR_SURFACE` | create open callback only when surface exists | explicit selection advances selection and session; cancellation advances session | permission/open failure |
| Camera `onOpened` | `OPENING` | `CONFIGURING_PREVIEW` | accept device lease; create preview config | require selection + session match; stale callback closes delivered device | `StaleSession`, configuration errors |
| Session `onConfigured` | `CONFIGURING_PREVIEW` | `PREVIEWING` | accept session, start first repeating request | require session match; stale callback closes delivered session | request rejection / stale |
| First valid frame | `PREVIEWING` | `PREVIEWING` | mark trace/post-frame gate | generation-bound; no state churn | `PreviewTimeout` handled by watchdog |
| Runtime `switch(route)` | `PREVIEWING`, error, waiting, configuring, RAW, or restoring states | `SWITCHING` | cancel any capture/write transaction; close session/device | advance selection + session and invalidate capture token before cleanup; stale callbacks close their resources | close/open errors typed separately |
| Switch cleanup complete | `SWITCHING` | `OPENING` or `WAITING_FOR_SURFACE` | create new open callback if surface ready | same command cannot silently select another canonical lens | open failures |
| Runtime `capture` | `PREVIEWING` | `CONFIGURING_RAW` | create capture token, context, bounded RAW output | capture cancellation restores preview; stale image closes immediately | `RawUnsupported`, session rejection |
| RAW session configured | `CONFIGURING_RAW` | `CAPTURING_RAW` | issue exactly one RAW request | token + selection + session must match | request rejection |
| RAW result/image partial | `CAPTURING_RAW`, `PAIRING_RAW` | `PAIRING_RAW` | insert into bounded timestamp index | overflow closes orphan image; timeout cancels transaction | `RawPairTimeout` |
| Exact pair | `PAIRING_RAW` | `WRITING_DNG` | transfer image ownership to writer transaction | token checked before transfer; later selection makes result stale and closes image | `StaleCapture` |
| Write success/failure | `WRITING_DNG` | `RESTORING_PREVIEW` | publish/delete row; close image; destroy RAW output | cancellation deletes pending row; never changes camera trust | DNG/MediaStore failure |
| Restore configured | `RESTORING_PREVIEW` | `PREVIEWING` | accept preview-only session | selection/session check; stale session closes | preview structural/transient failure |
| Lifecycle `pause` | every non-closed state | `PAUSING` | cancel transaction; close all Camera2/RAW resources | advance session and invalidate capture token before close; retain optical selection identity | close failures remain diagnostic |
| Pause cleanup | `PAUSING` | `WAITING_FOR_SURFACE` | release camera-owned surface lease, not UI view | late callbacks can only close delivered resources | none |
| Owner `close` | every state | `CLOSED` | close all resources, dispatcher last | idempotent; advance session and invalidate capture token | none exposed to UI |
| Transient platform failure | active states | `RECOVERABLE_ERROR` | close invalid leases | trust becomes temporarily unavailable only | typed transient |
| Structural profile failure | open/config/capture/restore | `STRUCTURAL_ERROR` | close invalid leases | policy may retry sibling profile under same canonical fingerprint | typed structural |

## Generation rules

- `SelectionGeneration` changes only for explicit optical selection or invalidation.
- `SessionGeneration` changes before every device/session replacement and lifecycle close.
- `CaptureToken` is unique per shutter transaction and is invalidated before cancellation cleanup.
- Callback acceptance requires every generation it captured to match. A route string match alone is
  insufficient because Android may reuse the same ID across multiple session attempts.
- Stale callbacks never publish an error against current state and never close a resource fetched
  from current owner state; they close only the resource delivered to or created by that callback.
