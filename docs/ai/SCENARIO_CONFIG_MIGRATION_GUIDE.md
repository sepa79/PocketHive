# Scenario Config Migration Guide

This guide is for agents migrating scenario bundles to the Bee Config SSOT
contract.

## Target

`template.bees[].config` is the public effective worker config. It must have
the same shape as worker `status-full.data.config`, swarm-controller
`status-full.data.context.workers[].config`, capability `config[].name` paths,
and runtime `config-update` patches.

Do not keep compatibility wrappers. Scenario YAML must not use
`config.worker`, `config.worker.config`, or `config.pockethive`.

Scenario authoring has one node key: `template.bees[].role`. Do not keep
`template.bees[].id`, and do not use `topology.edges[].from/to.beeId`.
Topology endpoints must use `role`.

## Mechanical Rewrites

Before:

```yaml
template:
  bees:
    - id: genA
      role: generator
      image: generator:latest
topology:
  edges:
    - from: { beeId: genA, port: out }
```

After:

```yaml
template:
  bees:
    - role: genA
      image: generator:latest
topology:
  edges:
    - from: { role: genA, port: out }
```

`role` is the unique scenario node key. Worker type comes from `image` and the
capability manifest. If old `id` and old `role` differ, the migrator moves the
old `id` value into `role` so topology keeps pointing at the same scenario node.
If a topology endpoint already has a conflicting `role`, the migrator fails and
requires manual editing.

Before:

```yaml
config:
  worker:
    message:
      path: /test
```

After:

```yaml
config:
  message:
    path: /test
```

Before:

```yaml
config:
  worker:
    config:
      baseUrl: "{{ sut.endpoints['default'].baseUrl }}"
```

After:

```yaml
config:
  baseUrl: "{{ sut.endpoints['default'].baseUrl }}"
```

Before:

```yaml
config:
  pockethive:
    worker:
      config:
        mode:
          type: finite
```

After:

```yaml
config:
  mode:
    type: finite
```

## IO Selector Migration

Scenarios must declare `config.inputs.type` / `config.outputs.type`
explicitly whenever they include an IO-specific config subblock.

Examples:

```yaml
config:
  inputs:
    type: REDIS_DATASET
    redis:
      listName: ph:dataset
```

```yaml
config:
  outputs:
    type: REDIS
    redis:
      defaultList: ph:out
```

The migrator derives selector requirements from Scenario Manager capability
manifests:

- IO manifests with `ui.ioScope: INPUT` map subblocks such as `inputs.redis`
  or `inputs.csv` to `inputs.type`.
- IO manifests with `ui.ioScope: OUTPUT` map subblocks such as `outputs.redis`
  to `outputs.type`.
- The required selector value is `ui.ioType`.

The tool may add a missing selector only when the scenario has exactly one
known IO subblock for that scope. It must fail and require manual editing when:

- more than one known IO subblock exists under the same `inputs` / `outputs`
  root
- an existing selector conflicts with the subblock
- the scenario is meant to use capability manifests outside the default
  `scenario-manager-service/capabilities` directory and the author did not pass
  `--capabilities-dir`

Do not infer selectors from role names, topology edges, queues, runtime
metadata, capability defaults, or worker defaults.

When an IO selector is already present, the tool also checks required fields from
the selected IO manifest. It may fill a missing field only when the migrator has
a hardcoded safe explicit value for that exact path, such as
`inputs.scheduler.maxMessages: 0`, Redis ports/SSL flags, CSV timing knobs, or
Redis output mode knobs. For Redis output, it may fill missing `routes` with
`[]` and missing `targetListTemplate` / `defaultList` with blank strings only
when the scenario already declares at least one Redis output target (`routes`,
`targetListTemplate`, or `defaultList`). It must fail and require manual editing
for fields that need scenario intent, such as Redis list names, Redis dataset
sources, concrete output routes, target list templates, or default output lists.

## Tool

Use the standalone migrator:

```bash
npm install --prefix tools/scenario-config-migrate
node tools/scenario-config-migrate/cli.mjs check scenarios
node tools/scenario-config-migrate/cli.mjs migrate --dry-run scenarios
node tools/scenario-config-migrate/cli.mjs migrate scenarios
node tools/scenario-config-migrate/cli.mjs check --json scenarios
node tools/scenario-config-migrate/cli.mjs check --capabilities-dir scenario-manager-service/capabilities scenarios
```

Accepted paths are individual `scenario.yaml` / `scenario.yml` files or
directories. Directory traversal is limited to files with those names.

The migrator fails explicitly on conflicts. If a direct target key already
exists with a different value, resolve the scenario manually and rerun the
tool. Do not add fallback path resolution or prefix stripping.

The migrator also fails explicitly on ambiguous or conflicting IO selector
state. Add the selector manually and rerun:

```yaml
config:
  inputs:
    type: CSV_DATASET
    csv:
      filePath: /app/scenario/datasets/users.csv
```

The tool is standalone. It does not require a running PocketHive stack,
Scenario Manager, Orchestrator, or MCP server.

## Out Of Scope

Do not rewrite worker application configuration, `application.yml`, service
defaults, or Spring property names such as `pockethive.worker.config.*`.
Those are runtime application properties, not scenario YAML wrappers.
