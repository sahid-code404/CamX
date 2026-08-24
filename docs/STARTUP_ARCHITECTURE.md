# Startup Architecture

## Valid-cache path

```text
process/activity create
  -> install one stable SurfaceView
  -> Android permission result + surface-ready gates
  -> in-memory SettingsSnapshot + tiny HotStartSnapshot
  -> validate cached environment and verified route
  -> CameraSessionController.open
  -> configure preview-only PRIVATE session with resolved FPS
  -> first capture result
  -> first valid preview frame
  -> release post-first-frame work
```

The two gates may arrive in either order. No modal app dialog, topology decode, full discovery,
network, update check, diagnostics formatting, deep AUX scan, RAW output, or Vulkan initialization is
on this path.

## First-install path

With no valid hot snapshot, seed discovery reads advertised public IDs and only the metadata needed
to choose one credible preview route by capabilities, facing, and optical evidence. IDs remain opaque.
The candidate opens immediately; complete Java/physical/NDK discovery and canonical reconciliation
continue only after first frame. A seed failure is recoverable UI state, not permission UI.

## Post-frame gate

The session owner records `FIRST_PREVIEW_FRAME` from a generation-valid callback. The runtime opens a
one-shot gate that may start topology reconciliation, noncritical cache enrichment, diagnostics, and
an in-process lifecycle OTA check. Backgrounding cancels these jobs. Resume does not create a service
and does not repeat completed work unless policy says it is stale.

Performance is evaluated as distributions on identical hardware; no universal fixed-millisecond
promise is made.
