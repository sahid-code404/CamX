# CamX

CamX is a universal Android camera platform under an architecture-first rebuild. The current
implementation frontier is CAMX-108 one-shot RAW capture. The repository establishes ownership,
state, cache, topology, preview, RAW, native-memory, and debug-OTA contracts; it does not claim the
future computational photo/video architecture is implemented or physically certified.

The permanent development package is `com.sahidcode404.camx`, so CamX and the reference CameX app
can be installed together for hardware A/B testing.

## Verify

```bash
./scripts/verify-architecture.sh
./gradlew testDebugUnitTest lintDevOta assembleDevOta
```

Start with [the architecture constitution](docs/ARCHITECTURE_CONSTITUTION.md), then use
[the Computational RAW Architecture Revision 2](docs/COMPUTATIONAL_RAW_ARCHITECTURE.md) for the frozen
future imaging contract and [the implementation backlog](docs/IMPLEMENTATION_BACKLOG.md) for bounded
follow-up work.
