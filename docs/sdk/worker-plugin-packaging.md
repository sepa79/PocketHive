# PocketHive Worker Plugin Packaging Guide

This page describes how to build a PF4J worker plugin that the `worker-plugin-host` can load.

## Manifest Files

Each plugin jar **must** contain the following files:

- `plugin.properties` (PF4J):
  ```properties
  plugin.id=<unique id>
  plugin.version=<semver>
  plugin.provider=<team or owner>
  plugin.class=<fully qualified class extending PocketHiveWorkerPlugin>
  ```
- `META-INF/pockethive-plugin.yml` (PocketHive metadata):
  ```yaml
  role: generator              # worker role exposed to the control plane
  version: 0.13.7              # plugin semantic version
  configPrefix: pockethive.workers.generator
  defaultConfig: config/defaults.yaml
  capabilities:
    - scheduler
  ```
- `config/defaults.yaml`: optional defaults merged by the host before control-plane overrides.

## Extension & Plugin Contracts

Each plugin ships two building blocks:

1. A PF4J plugin entry point extending `io.pockethive.worker.plugin.api.PocketHiveWorkerPlugin`. This is the class referenced by `plugin.class` and simply satisfies PF4J's lifecycle.
    ```java
    public final class GeneratorWorkerHostPlugin extends PocketHiveWorkerPlugin {
        public GeneratorWorkerHostPlugin(PluginWrapper wrapper) {
            super(wrapper);
        }
    }
    ```
2. A `PocketHiveWorkerExtension` that exposes the worker configuration the host should load.

```java
@Extension
public class GeneratorWorkerPlugin implements PocketHiveWorkerExtension {
    public String role() { return "generator"; }
    public Class<?>[] configurationClasses() { return new Class<?>[]{Application.class}; }
}
```

The host creates a child Spring `ApplicationContext` using these configuration classes, then wires the worker into the standard Worker SDK runtime.

## Configuration Precedence

When the host starts a plugin, configuration merges in this order:

1. **Plugin defaults** — `defaultConfig` inside the jar (if present).
2. **Host overrides** — files under `pockethive.plugin-host.overrides-dir` (e.g., `config/plugins/<role>.yaml`).
3. **Control-plane overrides** — runtime updates delivered through the PocketHive control plane.

Each stage can override fields written previously; later stages win.

## Packaging Script

Use `scripts/package-plugin.sh` to produce a distributable jar:

```bash
./scripts/package-plugin.sh --module generator-service --version 0.13.7
```

Flags:
- `--module` (required): Maven module to build.
- `--version`: tag appended to the output jar name (defaults to `$POCKETHIVE_VERSION` or the module's `project.version`).
- `--output`: destination directory (defaults to `dist/plugins`).
- `--run-tests`: run module tests before packaging (tests skipped by default).

The script verifies that the jar contains both `plugin.properties` and `META-INF/pockethive-plugin.yml`.
