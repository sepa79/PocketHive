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

## Mechanical Rewrites

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

## Tool

Use the standalone migrator:

```bash
npm install --prefix tools/scenario-config-migrate
node tools/scenario-config-migrate/cli.mjs check scenarios
node tools/scenario-config-migrate/cli.mjs migrate --dry-run scenarios
node tools/scenario-config-migrate/cli.mjs migrate scenarios
node tools/scenario-config-migrate/cli.mjs check --json scenarios
```

Accepted paths are individual `scenario.yaml` / `scenario.yml` files or
directories. Directory traversal is limited to files with those names.

The migrator fails explicitly on conflicts. If a direct target key already
exists with a different value, resolve the scenario manually and rerun the
tool. Do not add fallback path resolution or prefix stripping.

The tool is standalone. It does not require a running PocketHive stack,
Scenario Manager, Orchestrator, or MCP server.

## Out Of Scope

Do not rewrite worker application configuration, `application.yml`, service
defaults, or Spring property names such as `pockethive.worker.config.*`.
Those are runtime application properties, not scenario YAML wrappers.
