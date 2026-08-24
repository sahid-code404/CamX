# CI and Architecture Guards

The read-only validation job runs whitespace, all architecture guards, host C++ tests, Kotlin tests,
lint, all-ABI JNI build, fixed-signer `devOta` assembly, APK package/signer/version inspection, and
artifact hashing. It triggers on `main`, `rewrite/**`, `phase/**`, pull requests, and manual validation.

Publishing is a reusable workflow invoked only by a push job that `needs: validate`. It downloads the
exact current-run artifact and rechecks source/hash/package/signer binding before updating
`dev-latest`. Validation cancellation is job-scoped, so an already-started publisher cannot be
cancelled halfway by a newer push. Cross-branch publishers are globally serialized and a remote
manifest/APK freshness check prevents rolling backward. Build jobs have read-only repository
permission; only the gated publisher receives contents write, and no repository secrets are inherited.

Guards reject obvious regressions: brand/model/SoC/numeric-ID routing, camera opens outside the sole
owner, Camera2 ownership imports in UI, services/workers, blocking/global coroutine patterns,
DataStore/network in camera hot boundaries, native camera control/private libraries/bare ownership,
RAW global registries, missing resource ownership entries, signer drift, package suffixes, and
publication without a green dependency. Guards complement tests/review; they do not prove semantic
correctness or hardware support.
