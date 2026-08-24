# Preview Architecture

`SurfaceViewPreviewSurface` is the initial backend because it can feed a Camera2 PRIVATE surface
without routing pixels through Compose or an application ImageReader. The view is remembered for the
screen lifetime. Surface lifecycle produces explicit leases whose identities and session generations
must match before attach/detach; an old callback cannot unbind a newer surface.

CAMX-104 keeps preview stream choice pure. `AUTO` prefers an advertised `CAMERA2_PRIVATE` candidate
when cadence evidence is equivalent because it is the display-oriented path. Explicit PRIVATE or YUV
requests filter strictly to that advertised stream type. Selecting a YUV capability here does not
create a YUV owner, ImageReader, processing pipeline, or UI surface integration; those remain separate
resource/integration concerns.

Responsive size selection is relative to the actual view and rotated stream geometry, never a fixed
resolution or aspect ratio. Normal preview targets roughly one effective source pixel per view pixel
after center crop. High-resolution preference targets two-times linear oversampling (four-times
effective source pixels), then chooses the closest cadence-compatible advertised candidate instead of
blindly selecting the largest stream. A bounded candidate limit fails closed before sorting.

`PreviewGeometryCalculator` derives normalized rotation from sensor orientation, display rotation, and
facing, swaps stream axes at 90/270 degrees, then applies one uniform center-crop scale. Rendered width
and height cover the view, translation centers the crop, and horizontal mirroring is enabled only for
an explicitly mirrored FRONT preview. No previous-lens transform is retained.

`PreviewFpsResolver` remains the sole FPS-resolution policy. CAMX-104 calls it once per bounded stream
candidate so known minimum-frame-duration evidence can reject or demote cadence-incompatible streams;
unknown duration remains unknown rather than being fabricated. `PreviewFrameMetrics` remains unchanged
and outside this ticket.

A resolved `PreviewConfiguration` uses only an advertised concrete stream type and size. Its `pv1`
signature is deterministic and contains requested stream policy, resolved type/size, high-resolution
state, FPS request, resolved FPS range, and FPS fallback reason. No timestamp, object identity, locale,
camera-ID meaning, or random value participates.
