# Scenario Config Migrator

Standalone migrator for the Bee Config SSOT scenario authoring contract.

It rewrites only scenario bundle files named `scenario.yaml` or
`scenario.yml`. It does not read or rewrite application config files,
Spring properties, runtime directories, or generated artifacts.

The migrator covers:

- legacy scenario authoring fields: `template.bees[].id` and topology endpoint
  `beeId`
- legacy `config.worker` / `config.pockethive` wrapper removal
- missing explicit IO selectors when exactly one known IO subblock is present
- missing required fields for the selected IO manifest when the tool has a
  hardcoded safe explicit value for that field

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
selectors, mismatched IO selectors, ambiguous IO subblocks, or missing required
selected-IO fields.

## Migrate

```bash
node tools/scenario-config-migrate/cli.mjs migrate --dry-run scenarios
node tools/scenario-config-migrate/cli.mjs migrate scenarios
```

The migrator stops on conflicts. It never merges different source and target
values. Resolve the reported scenario path and bee manually, then rerun it.

For scenario authoring, `migrate` rewrites old `.31` node ids into the new
single key:

```yaml
template:
  bees:
    - id: genA
      role: generator
topology:
  edges:
    - from: { beeId: genA, port: out }
```

becomes:

```yaml
template:
  bees:
    - role: genA
topology:
  edges:
    - from: { role: genA, port: out }
```

`role` is the unique scenario node key. Worker type comes from `image` and the
capability manifest, not from a second id field. If a topology endpoint already
has a conflicting `role`, or points to a role that is not declared in
`template.bees`, the tool fails and requires manual editing.

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

For selected IO manifests, `migrate` may also write missing required fields only
when the field has an explicit safe value in the migrator, for example
`inputs.scheduler.maxMessages: 0`, Redis ports, Redis SSL flags, CSV timing
knobs, and Redis output mode knobs. For Redis output, routes may be filled as
`[]` and `targetListTemplate` / `defaultList` may be filled as blank strings
only when the scenario already declares at least one Redis output target
(`routes`, `targetListTemplate`, or `defaultList`). Fields without a safe
explicit value still fail and require manual editing; the tool does not invent
concrete list names, Redis sources, output routes, or target list templates.
