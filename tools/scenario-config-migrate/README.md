# Scenario Config Migrator

Standalone migrator for the Bee Config SSOT scenario authoring contract.

It rewrites only scenario bundle files named `scenario.yaml` or
`scenario.yml`. It does not read or rewrite application config files,
Spring properties, runtime directories, or generated artifacts.

The migrator covers:

- legacy `config.worker` / `config.pockethive` wrapper removal
- missing explicit IO selectors when exactly one known IO subblock is present

IO selector requirements are derived from Scenario Manager capability
manifests. By default the tool reads
`scenario-manager-service/capabilities`; use `--capabilities-dir <dir>` when
validating scenarios against a different manifest set.

## Install

```bash
npm install --prefix tools/scenario-config-migrate
```

## Check

```bash
node tools/scenario-config-migrate/cli.mjs check scenarios
node tools/scenario-config-migrate/cli.mjs check --capabilities-dir scenario-manager-service/capabilities scenarios
node tools/scenario-config-migrate/cli.mjs check --json scenarios
```

`check` exits non-zero when it finds previous-format bee config, missing IO
selectors, mismatched IO selectors, or ambiguous IO subblocks.

## Migrate

```bash
node tools/scenario-config-migrate/cli.mjs migrate --dry-run scenarios
node tools/scenario-config-migrate/cli.mjs migrate scenarios
```

The migrator stops on conflicts. It never merges different source and target
values. Resolve the reported scenario path and bee manually, then rerun it.

For IO selectors, `migrate` only writes `inputs.type` / `outputs.type` when the
scenario contains exactly one known IO subblock for that scope, for example:

```yaml
config:
  inputs:
    redis:
      listName: ph:dataset
```

becomes:

```yaml
config:
  inputs:
    redis:
      listName: ph:dataset
    type: REDIS_DATASET
```

If multiple IO subblocks exist, or an existing selector conflicts with a
subblock, the tool fails and requires a manual decision. It does not infer from
role names, topology, queues, runtime metadata, or capability defaults.
