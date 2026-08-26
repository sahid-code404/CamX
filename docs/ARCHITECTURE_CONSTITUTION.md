# Architecture Constitution

This document is the highest-level engineering contract for CamX. A conflicting implementation,
ticket, test, or convenience is wrong until this document is changed through an explicit Tier-A
architecture migration.

## Mandatory invariants

1. Exactly one active camera owner exists: `CameraSessionController` owns every `CameraDevice`,
   `CameraCaptureSession`, session output, and camera callback dispatcher.
2. UI never opens or closes a `CameraDevice`; it issues typed intent to the runtime boundary.
3. Discovery reads metadata but never opens a `CameraDevice`.
4. RAW capture may borrow only a bounded transaction lease from the session owner. DNG writers
   consume immutable transaction inputs; neither may open a `CameraDevice` or own session/output
   authority.
5. Camera IDs are opaque transport identifiers. Manufacturer, model, SoC, sensor name, and numeric
   ID have no production routing meaning.
6. Advertised topology is evidence, not runtime trust. Metadata, preview, and RAW trust are separate.
7. A real optical `CanonicalLens` can own multiple transport `CameraProfile` values.
8. Automatic failover may select only another profile of the same canonical lens.
9. Storage, encoding, cancellation, and MediaStore failures never damage route or camera trust.
10. Every asynchronous callback is generation-bound. A stale callback closes only resources it owns
    and can never mutate active selection, session, surface, capture, or UI state.
11. Persistent settings are never queried from camera open, switch, request-building, capture, or
    first-frame paths. Runtime reads an immutable in-memory snapshot.
12. Normal startup is cache-first. Valid hot cache may bypass complete discovery until after frame 1.
13. Network, OTA, deep discovery, and diagnostics formatting wait until a verified first frame.
14. Idle photo preview attaches only display outputs. RAW is transaction-scoped and destroyed after
    capture before preview restoration.
15. Every queue, timestamp index, metrics buffer, trace, worker pool, and native buffer pool has an
    explicit capacity and overflow policy.
16. Native resources have deterministic RAII ownership; bare owning pointers and undocumented JNI
    global references are forbidden.
17. Ordinary camera and debug-OTA behavior uses no `Service`, `ForegroundService`, `JobService`, or
    `WorkManager`.
18. The `devOta` build stays debuggable, keeps package `com.sahidcode404.camx`, and uses one permanent
    committed development signer with API-23-compatible v1 and v2 APK signatures. Regenerating it
    breaks the channel.
19. State, ownership, topology, cache identity, JNI, RAW pairing, signer verification, and guard
    changes require Tier-A review.
20. The application compatibility baseline is exactly Android API 23. The Gradle model, every variant,
    merged APK, development manifest, and published artifact must agree; raising the floor is an
    explicit Tier-A constitutional migration, never an incidental dependency or toolchain result.
21. `libcamx_core.so` is loadable on API 23 for every packaged ABI and has no unconditional dependency
    on a later or private platform library. Camera NDK metadata and media-image ownership begin at API
    24; hardware-buffer ownership begins at API 26. A device API level alone never makes an optional
    native backend available: implementation, public-library, and complete-symbol probes must agree.
22. Android Camera2 in Kotlin is the only camera device/session control plane on every supported API.
    Optional native code may supply measured metadata or data-plane work but cannot open a camera,
    become a second owner, or load a private/vendor camera library.
23. Rejection of requested preview options is nonstructural evidence and permits exactly one new
    `SAFE_BASELINE` configuration attempt without changing persistent trust. Rejection of that known
    safe baseline is structural, rejects preview trust for that profile, and alone permits
    same-canonical failover.
24. Generation equality is necessary but not sufficient for asynchronous mutation. A callback must
    consume the exact owner-, stage-, intent-, surface-, attempt-, and generation-bound one-shot
    permit before it may publish state or adopt a delivered resource. Stale resources close exactly
    once; invalidation happens before cleanup, and cleanup completion cannot overwrite newer intent.
25. CI build, emulator runtime, and physical-device results are separate evidence classes. A green
    build or static APK/ELF inspection cannot be reported as API-23 launch evidence or physical-camera
    compatibility.
26. Computational processing consumes immutable, generation-bound acquisition handoffs; it cannot
    change the camera ownership model without an explicit Tier-A architecture migration.
27. Representation truth is structural. Public interpretable sensor-domain data, camera-processed
    data, opaque transport, Sensor negatives, computational negatives, and processed-source masters
    are distinct types and may not be relabeled for product convenience.
28. Sensor products contain no CamX sample-changing reconstruction. Computational CFA output is legal
    only on a genuine CFA grid; demosaiced, super-resolved, geometry-reconstructed, or full-color
    output is linear and may not be remosaiced merely to look like Sensor RAW.
29. RAW-video container and codec implementations are replaceable behind frozen contracts.
    `PACKED_NONE` is the mandatory admission-safe codec baseline; capture admission cannot depend on
    an expected compression ratio.
30. V1 computational execution is bounded, lifecycle-scoped, and in-process. No `Service`,
    `ForegroundService`, `JobService`, or `WorkManager` is introduced merely to host compute; a
    separate compute process requires a future Tier-A ADR and prototype.
31. Computational support is certified for an exact profile and implementation configuration.
    Discovery, advertised capability, CI, emulator evidence, and physical-device certification remain
    different evidence states.

The authoritative detailed contract for invariants 26-31 is
[`COMPUTATIONAL_RAW_ARCHITECTURE.md`](COMPUTATIONAL_RAW_ARCHITECTURE.md). Its Revision 2 semantic and
constitutional decisions are frozen; implementation candidates remain provisional until their stated
prototype and physical-proof gates pass.

## Simplicity test

Every abstraction must state the state or resource it owns, the invariant it enforces, and its
failure boundary. A pass-through abstraction that answers none of those is removed. Immutable value
objects and pure policies are not bureaucracy: they prevent identity confusion and make platform
behavior testable without a device.

## Universal compatibility exceptions

A device-specific compatibility rule is allowed only after the generic capability path was tried,
hardware evidence proves the need, scope is narrowly fingerprinted to observable behavior, the
reason and expiry condition are recorded in an ADR, and tests prove unrelated environments cannot
inherit it. Brand or camera-ID dispatch is never an acceptable shortcut.
