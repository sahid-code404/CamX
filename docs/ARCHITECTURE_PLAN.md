# CamX Architecture Plan

Status: foundation accepted; current implementation frontier is CAMX-108 one-shot RAW capture.
CamX Computational RAW Architecture Revision 2 is semantically and constitutionally frozen in
[`COMPUTATIONAL_RAW_ARCHITECTURE.md`](COMPUTATIONAL_RAW_ARCHITECTURE.md). No computational milestone is
implemented by that documentation freeze.

## Historical foundation sprint boundary

That foundation sprint created a compilable, launchable application and executable contracts. It did
not claim complete discovery, physical-camera validation, RAW capture, or computational photography.
The foundation checkpoint was considered complete only when the JVM tests, native tests, architecture guards,
lint, all-ABI JNI build, signed `devOta` APK, package check, and signer check pass. API-23 support has
an additional exact contract: the Android model and produced APK declare 23, the baseline native core
is built for and linkable on 23 in every ABI, and the development APK carries both v1 and v2
signatures. “Launchable on API 23” is not complete until a recorded API-23 emulator or device run
installs, starts, and loads the native core.

## Dependency direction

```text
feature + ui  -> immutable camera/settings/update models
app runtime   -> session, topology, cache, preview, raw contracts
session       -> sole Kotlin/Android Camera2 control plane + preview/raw surface contracts
topology      -> immutable evidence only
cache         -> immutable snapshots only
raw           -> session-owned transaction lease + save port
JNI           -> coarse immutable batches and resource counters
native/core   -> API-23 baseline JNI, bounded containers, API-neutral RAII
optional NDK  -> later Tier-A public metadata/data-plane modules, capability-gated by API/library/symbol
```

No lower layer depends on a feature package. Discovery cannot depend on session. RAW cannot open a
camera. Native processing cannot reach UI. The future processing graph accepts immutable,
representation-typed acquisition handoffs and has no authority over the acquisition session. No
optional native module is a load-time dependency of the API-23 core, and no native module may become
a second camera control plane.

## Foundation increments

1. Document the CameX behaviors and failure lessons before porting code.
2. Establish value types, immutable snapshots, generations, typed failures, and a pure transition
   model.
3. Establish one `CameraSessionController` ownership boundary and one serialized camera dispatcher.
4. Establish independent evidence producers and a pure conservative topology resolver.
5. Establish tiny hot-start and full-topology cache ports plus a memory-first settings snapshot.
6. Establish stable SurfaceView, preview geometry/FPS policy, and bounded metrics contracts.
7. Establish a bounded RAW transaction and exact timestamp pairer, without enabling capture.
8. Establish coarse JNI, RAII wrappers, bounded native structures, counters, and host-native tests.
9. Establish low-frequency UI state, system permission flow, design tokens, icon, and splash.
10. Establish signer-pinned debug OTA, verification, installer boundary, CI, and source guards.
11. Establish the exact API-23 application/native floor, typed optional-native availability, one-shot
    asynchronous permits, and requested-to-safe-baseline preview fallback contract.

## Startup integration order

Permission and a stable surface are independent gates. Once both exist, runtime reads the already
materialized settings snapshot and tiny hot snapshot, validates the environment fingerprint, and
asks the session owner to open the verified route. On cache miss, seed discovery reads only enough
public Camera2 metadata to propose one credible route. `FIRST_PREVIEW_FRAME` releases a post-frame
gate for full reconciliation, diagnostics enrichment, persistence, and OTA. No network or deep AUX
scan is allowed ahead of that gate. The Java Camera2 path remains authoritative on API 23 and newer;
post-frame native evidence is used only when an implemented optional backend passes its minimum-API,
public-library, and complete-symbol probes.

## Review gates

Tier-A review owns state, ownership, topology, cache identity, JNI, the API floor and native linkage,
RAW pairing, and signer changes.
Tier-B work implements pure policy or diagnostics against those contracts. Tier-C work is restricted
to presentation, resources, documentation, and isolated tests. Protected paths and ticket-level
allowed/forbidden files are defined in `AI_TASK_POLICY.md` and `IMPLEMENTATION_BACKLOG.md`.

## Computational architecture dependency lane

CAMX-108 establishes the trustworthy one-shot sensor acquisition primitive. It feeds M1 rather than
absorbing future burst, graph, codec, container, DNG, or video implementation. The frozen dependency
order is:

```text
M0 -> M1
M1 -> {M2A, M2B, M3, M8A}
M3 -> M4 -> M5 -> M6 -> M7
M7 -> {M8B, M9}
M2A + M2B + M4 -> M10
M7 + M10 -> M11
M9 + M10 + M11 -> M12
M7 + M11 -> M13 -> M14
```

M2A selects or rejects a RAW-video container behind `RawVideoContainerContract`; M2B selects optional
reversible codecs while retaining mandatory `PACKED_NONE`. M8A validates Sensor DNG independently;
M8B chooses a computational DNG implementation only after a real computational-negative contract
exists. The master architecture owns milestone goals, scope, proof gates, and rollback boundaries.

## Exit evidence

CI static validation proves source/model policy, compilation, merged-APK identity, signing, and
all-ABI ELF compatibility only. A separate API-23 emulator run may establish install/start/native-load
evidence for that virtual environment. Neither result asserts lens availability, sustained FPS, DNG
validity, lifecycle leak freedom, or physical-device support; those require the matrix in
`HARDWARE_ACCEPTANCE.md` and a recorded CameX-versus-CamX benchmark distribution on the same device.
