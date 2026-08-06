# Upgrading PocketHive

| Reader context | Details |
| --- | --- |
| Audience | PocketHive operators and scenario maintainers preparing a version change |
| Prerequisites | The current and target release notes, a recoverable backup, and access to validate affected scenario bundles |
| Expected outcome | Apply required contract migrations and validate the result before changing the running PocketHive version |
| Last verified source | Unreleased `0.15.36` lifecycle-control-plane line; qualification evidence must record the exact tested commit |

This is the canonical index of required actions when upgrading PocketHive.
Release summaries remain in `CHANGELOG.md`; detailed scenario rewrite rules
and commands live in `docs/ai/SCENARIO_CONFIG_MIGRATION_GUIDE.md`.

PocketHive does not implicitly accept legacy contract shapes. Complete the
documented migration before deploying a release that removes an old shape.

## Existing ClickHouse volumes: `tx_outcome` v1 to v2

Fresh ClickHouse volumes create only `ph_tx_outcome_v2`. Existing volumes may
still contain the legacy `ph_tx_outcome_v1` table.

The local ClickHouse service runs the official ClickHouse entrypoint, waits for
readiness, and then runs the v1-to-v2 migration inside the same container. On
startup it:

- exits successfully when `ph_tx_outcome_v1` does not exist;
- migrates all `ph_tx_outcome_v1` rows when v1 exists and v2 is empty;
- drops `ph_tx_outcome_v1` after a successful full migration;
- fails startup instead of appending to a non-empty v2 table, avoiding duplicate
  historical rows.

For manual migration or recovery, run the repository wrapper from the checkout:

```bash
bash clickhouse/run-tx-outcome-v1-to-v2-migration.sh
```

Optional bounded or destructive modes must be explicit:

```bash
# Migrate a date range.
bash clickhouse/run-tx-outcome-v1-to-v2-migration.sh \
  --from 2026-02-01 --to 2026-02-22

# Rebuild v2 from v1 after deliberately truncating the current v2 table.
bash clickhouse/run-tx-outcome-v1-to-v2-migration.sh --truncate-v2

# Drop v1 only after a successful full-table migration.
bash clickhouse/run-tx-outcome-v1-to-v2-migration.sh \
  --drop-source-after-migration
```

The wrapper copies and runs `clickhouse/migrate-tx-outcome-v1-to-v2.sh` inside
the running `clickhouse` Compose service. The migration script checks that v1
exists, creates v2 from the repository schema when needed, migrates day by day,
and compares source and target row counts after each day. It refuses to append
to a non-empty v2 table unless `--truncate-v2` is supplied explicitly.

Back up the ClickHouse volume before using a destructive option. Do not drop v1
or change the running PocketHive version until the migration completes and the
row-count checks pass.

## 0.15.36 (from 0.15.35)

### SUT registry contract SSOT

Scenario Manager now reads and returns the shared `SutEnvironment` contract
directly. Remove `ui` blocks from `scenario-manager-service/sut/sut-environments.yaml`;
presentation hints are not part of the runtime SUT contract. Environment
`id`, `name`, optional `type`, endpoint map keys and endpoint values must be
non-blank. The `endpoints` object is required.

This is an intentional strict contract change. There is no parser fallback for
the former Scenario Manager DTO.

## 0.15.35 (from 0.15.34 and earlier)

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

## Troubleshooting

If the migrator reports `SUT_ENDPOINT_ID_CONFLICT`, resolve the named map key
and nested identifier explicitly; do not discard either value automatically.
For validation failures, use the reported scenario contract field and the
[scenario contract](scenarios/SCENARIO_CONTRACT.md). For runtime symptoms after
an approved upgrade, follow
[observability and troubleshooting](guides/operators/observability-troubleshooting.md).

## Next step

After every required migration and validation succeeds, return to the
[deployment update and recovery procedure](guides/operators/deployment.md#update-and-recovery)
for the selected delivery path.
