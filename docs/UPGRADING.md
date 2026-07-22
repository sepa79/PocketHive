# Upgrading PocketHive

This is the canonical index of required actions when upgrading PocketHive.
Release summaries remain in `CHANGELOG.md`; detailed scenario rewrite rules
and commands live in `docs/ai/SCENARIO_CONFIG_MIGRATION_GUIDE.md`.

PocketHive does not implicitly accept legacy contract shapes. Complete the
documented migration before deploying a release that removes an old shape.

## Unreleased (from 0.15.35 and earlier)

### SUT endpoint identity

The key in a SUT environment's `endpoints` map is now the only endpoint
identifier. Nested endpoint `id` fields have been removed from the contract and
are rejected by canonical Scenario Manager bundle validation.

Before:

```yaml
id: wiremock-local
name: WireMock local
endpoints:
  default:
    id: default
    kind: HTTP
    baseUrl: http://wiremock:8080
```

After:

```yaml
id: wiremock-local
name: WireMock local
endpoints:
  default:
    kind: HTTP
    baseUrl: http://wiremock:8080
```

Run the repository migrator against one bundle or a directory of bundles:

```bash
npm install --prefix tools/scenario-config-migrate
node tools/scenario-config-migrate/cli.mjs check scenarios
node tools/scenario-config-migrate/cli.mjs migrate --dry-run scenarios
node tools/scenario-config-migrate/cli.mjs migrate scenarios
node tools/scenario-config-migrate/cli.mjs check scenarios
```

The migrator removes a nested endpoint `id` only when it matches the map key or
is null. If the nested value differs from the key, it reports
`SUT_ENDPOINT_ID_CONFLICT` and leaves the file unchanged. Resolve that conflict
by choosing the intended map key and deleting the nested field.

After migration, validate bundles through the official Scenario Manager
validation ingress before deployment. Do not add a parser fallback or retain
both endpoint identity shapes.
