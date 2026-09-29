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

## Workspace catalogue

Workspace entries are durable metadata, stored in `workspace-catalogue.json` in
the configured `tcp-mock.data-directory` (supplied value `/app/data`). Retain the
instance data volume. Selection does not isolate mappings, TCP traffic or users,
and does not attach scenarios, swarms or SUTs. The dropdown supports rename,
delete and explicit reload after a failed request.

See the [workspace contract](../docs/tcp-mock/legacy-workspaces.md) for persistence
and failure semantics. Authentication uses the explicit `tcp-mock.auth.provider` setting: `NATIVE` for
standalone Basic login, or `POCKETHIVE` for the PocketHive login at `/tcp-mock/`.
The supplied Compose services select POCKETHIVE; native mode requires no auth-service.
Owner IDs include the provider and retain their attribution if the provider changes.
No automatic provider fallback is supported.

Select the mode with `TCP_MOCK_AUTH_PROVIDER`:

| Mode | Required configuration | Browser login |
|---|---|---|
| `NATIVE` | `POCKETHIVE_TCP_MOCK_DASHBOARD_USERNAME`, `POCKETHIVE_TCP_MOCK_DASHBOARD_PASSWORD` | Local username/password form |
| `POCKETHIVE` | `POCKETHIVE_AUTH_SERVICE_URL` | Existing PocketHive login, on the same origin and browser tab |

PocketHive mode expects the PocketHive UI to serve its shared `/auth-session.js`
module. The supplied Compose deployment provides that integration. Native mode
does not load that module or contact auth-service. PocketHive authentication outages
block administrative requests; TCP matching and responses continue. Switching mode
requires configuration and restart, and does not transfer existing workspace ownership.

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
