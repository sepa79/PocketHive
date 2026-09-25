# TCP Mock Server

TCP Mock Server is PocketHive's TCP/TCPS mock implementation. Functional
qualification is incomplete; current capability gaps and acceptance requirements
are tracked in [TCP capability qualification](docs/WIREMOCK-PARITY.md).

## Documentation

- [Start here](docs/START-HERE.md) — documentation ownership and navigation.
- [TCP capability qualification](docs/WIREMOCK-PARITY.md) — the authoritative
  capability assessment and required evidence.
- [Repository usage](../docs/USAGE.md) — supported runtime commands and ingress paths.

The legacy guides under `docs/` retain historical examples. They do not establish
production readiness, API compatibility, measured capacity or test coverage.
No runtime tests or performance measurements were run for this documentation update.

## Runtime mapping persistence

Mapping changes are stored as a complete snapshot in `/app/data/mapping-catalogue.json`.
Keep the instance's `/app/data` volume across restarts (the supplied Compose and
HiveForge deployments already do this). Use a separate data directory per instance;
multiple mock processes must not share it.

On first start only, the catalogue is initialized from built-in mappings and files
under `/app/mappings`. Once saved, the snapshot owns the complete catalogue: edits,
deletions and clearing all mappings survive restart. Changing seed files does not
change an initialized runtime. Removing the data volume creates a fresh runtime.

Authoring, admin stub operations and imports all persist through the same registry.
Each change replaces the snapshot atomically before becoming visible in memory.
Storage errors fail the operation; corrupt saved state fails startup. Bulk imports
remain sequential: entries accepted before a later failure remain saved. Unsupported
atomic replacement fails explicitly. This guarantees process-restart persistence,
not cross-process coordination or per-request durability of diagnostic match counts.

Legacy files under `/app/data/mappings` are not automatically migrated. Runtime
changes are not exported into PocketHive scenarios. Mock scenario state and request
journals have their own lifecycle; clearing mappings does not reset them.
