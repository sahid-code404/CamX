# AI Modification Policy

Architecture authority is based on change surface, not confidence claims.

## Tier A — architecture model required

May change `core/camera/session/**`, `runtime/**`, `topology/**`, `cache/**`, `raw/**`, native public
headers/ownership/JNI, update verification/signing, state machines, threading, CI/workflows,
build/toolchain identity, OTA packaging, and `scripts/verify-*`.
Every change must name the invariant, ownership transfer, stale behavior, failure classification,
unit tests, CI guard impact, and hardware acceptance step. Cross-boundary changes require an ADR.

## Tier B — contract implementation

May implement preview stream/size policy, FPS resolver, settings persistence, diagnostics formatting,
performance UI, integration tests, and other non-ownership-heavy features. Tier B consumes immutable
contracts and cannot add a Camera2 owner, alter canonical identity, change JNI ownership, or loosen a
guard. If a contract is insufficient, stop and open a Tier-A migration ticket.

## Tier C — bounded presentation/support

May implement Compose rows/screens, components, strings, icons/resource wiring, documentation, pure
unit tests, formatting, and isolated fixes outside protected paths. Tier C must not edit Tier-A paths
unless a Tier-A ticket explicitly lists the exact files and review checks.

## Protected paths

```text
app/src/main/java/com/sahidcode404/camx/core/camera/session/**
app/src/main/java/com/sahidcode404/camx/core/camera/runtime/**
app/src/main/java/com/sahidcode404/camx/core/camera/topology/**
app/src/main/java/com/sahidcode404/camx/core/camera/cache/**
app/src/main/java/com/sahidcode404/camx/core/camera/raw/**
app/src/main/java/com/sahidcode404/camx/core/update/verification/**
native/core/include/**
native/core/src/**/raw/**
native/core/src/**/jni/**
scripts/verify-*.sh
tools/dev-signing/**
.github/workflows/**
app/build.gradle.kts
build.gradle.kts
settings.gradle.kts
gradle/libs.versions.toml
gradle/wrapper/**
scripts/install-android-sdk.sh
scripts/package-dev-ota.sh
scripts/verify-packaged-ota.sh
```

Review rejects files outside a ticket's allowlist, undocumented new mutable state, an owner without a
close path, unbounded work, identity strings replacing value types, or tests weakened to accept a
regression.
