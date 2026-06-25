# Scenario Config Migrator

Standalone migrator for the Bee Config SSOT scenario authoring contract.

It rewrites only scenario bundle files named `scenario.yaml` or
`scenario.yml`. It does not read or rewrite application config files,
Spring properties, runtime directories, or generated artifacts.

## Install

```bash
npm install --prefix tools/scenario-config-migrate
```

## Check

```bash
node tools/scenario-config-migrate/cli.mjs check scenarios
node tools/scenario-config-migrate/cli.mjs check --json scenarios
```

`check` exits non-zero when it finds previous-format bee config.

## Migrate

```bash
node tools/scenario-config-migrate/cli.mjs migrate --dry-run scenarios
node tools/scenario-config-migrate/cli.mjs migrate scenarios
```

The migrator stops on conflicts. It never merges different source and target
values. Resolve the reported scenario path and bee manually, then rerun it.
