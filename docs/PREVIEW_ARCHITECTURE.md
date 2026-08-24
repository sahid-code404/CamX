# Preview Architecture

`SurfaceViewPreviewSurface` is the initial backend because it can feed a Camera2 PRIVATE surface
without routing pixels through Compose or an application ImageReader. The view is remembered for the
screen lifetime. Surface lifecycle produces explicit leases whose identities and session generations
must match before attach/detach; an old callback cannot unbind a newer surface.

Idle photo preview contains only the display surface. `AUTO` and `CAMERA2_PRIVATE` are initially
implemented policy values. `CAMERA2_YUV_420_888` remains a reserved enum and must not be exposed until
it renders or analyzes actual frames with a bounded owner; a drain-only ImageReader is not a feature.

`PreviewGeometryCalculator` recomputes rotation, center crop, translation, and explicit front-mirror
policy from current view/stream/orientation/facing inputs. It stores no previous-lens transform.

`PreviewFpsResolver` leaves the Camera2 key absent when override is off. When on, it chooses only an
advertised active-profile range that the selected stream can sustain; the resolved value is present
before repeating request 1. `PreviewFrameMetrics` consumes sensor timestamps into a fixed primitive
ring and formats average/p50/p95 only on snapshot.

High-resolution viewfinder policy is capability- and cadence-based. “On” means the largest supported
live stream that still satisfies requested cadence and current view geometry, not maximum sensor
mode. It remains a Tier-B implementation/hardware-validation ticket.
