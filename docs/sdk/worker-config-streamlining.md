# Worker Config Simplification Plan

Goal: cut down `WorkerControlPlaneRuntime` to the essentials so config handling becomes “default
POJO → merge incoming patch → publish”. We’ll follow the steps below **after** the current branch
stabilises and tests pass.

## 1. Typed Config State
Introduce a `ConfigState` (or extend `WorkerState`) that stores only the typed config for each
worker. Raw maps will be derived with `ObjectMapper` on demand (status snapshots, logging), removing
the dual `typedConfig/rawConfig` tracking that complicates merges today.

## 2. ConfigMerger Utility
Create a dedicated `ConfigMerger` responsible for:
- Accepting the current typed config + an incoming map (defaults or `config-update` payload)
- Producing the new typed config
- Returning a diff structure describing added/updated/removed fields for logging

`WorkerControlPlaneRuntime` should delegate to this utility instead of inlining relaxed-key logic
and diff generation.

## 3. Typed Defaults Only
Require every `PocketHiveWorkerProperties` implementation to expose a fully-typed default
configuration (constructed via Spring’s binder). Migration steps:
1. Ensure each `pockethive.workers.<role>` binding produces the POJO.
2. Remove `convertDefaultConfig` and the map-based seeding path.
3. Update services to rely on the typed default directly.

## 4. Control-Plane Notifier
Extract ready/error/logging emission into a small `ControlPlaneNotifier`. The
`handleConfigUpdate` path should reduce to:
```
targets -> ConfigMerger -> state.update -> notifier.ready/error
```
This keeps the runtime focused on state transitions rather than message formatting.

## 5. Delete Legacy Helpers
Once the merger + notifier are in place:
- Drop the relaxed-key conversion and pretty-print/diff helpers from `WorkerControlPlaneRuntime`.
- Remove any remaining raw-map plumbing in favour of the typed config + serializer on demand.

These steps will leave the runtime lean and make config flows predictable, while still letting
framework code emit the necessary control-plane confirmations and status updates.
