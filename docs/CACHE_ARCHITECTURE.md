# Cache Architecture

Cache is an optimization backed by verified evidence, never a source of new hardware truth.

## Hot start snapshot

The hot snapshot is a tiny independently decoded record containing schema, environment fingerprint,
selected canonical/profile fingerprints, opaque open/physical IDs, preview backend and size,
configuration signature, orientation/facing if known, route/preview trust, FPS override request,
high-resolution-viewfinder flag, and last successful route/session evidence. It contains no complete
stream list, diagnostics, alias graph, or formatted JSON.

Startup reads this record once on I/O, materializes it in memory, and validates all of: cache schema,
Android API, build fingerprint, and camera environment signature. A mismatch discards the record
without trying to repair individual fields. A verified route is attempted before full topology
decode or discovery.

## Full topology cache

The full cache contains immutable evidence and resolved topology: all routes, canonical lenses,
profiles, capabilities, logical/physical relationships, stream metadata, FPS ranges, RAW formats,
optical evidence, separate trust dimensions, provenance, grouping decisions, and diagnostics.
It is loaded/reconciled after first frame when hot cache is valid.

## Atomicity and ownership

`CameraCacheRepository` owns serialized cache replacement. Writers encode a new schema record to a
temporary file, flush, and atomically replace the prior record. Readers validate bounds before
allocation and return a typed miss on corruption. The session owner receives snapshots and has no
cache API, preventing I/O on open/switch/capture paths.

Successful preview verification may update memory immediately and enqueue persistence. Transient
failures never erase a known route. Structural evidence updates only the affected profile trust.
Cache sizes and evidence counts have explicit upper bounds; oversized or duplicate input is rejected.

## Tests and measurements

Tests cover every invalidation field, older/newer schema, truncation, checksum failure, unknown enum,
oversized counts, atomic replacement failure, and transient-versus-structural trust persistence.
Startup traces compare valid-cache characteristics reads, decode bytes, time-to-first-frame median,
and p90 against CameX on identical hardware.
