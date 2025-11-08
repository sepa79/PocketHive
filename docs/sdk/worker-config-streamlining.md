# Worker Config Simplification Plan

Goal: cut down `WorkerControlPlaneRuntime` to the essentials so config handling becomes “default
POJO → merge incoming patch → publish”. We’ll follow the steps below **after** the current branch
stabilises and tests pass.

## 1. Typed Config State ✔️
`WorkerState` now stores only the typed POJO. Raw maps are derived on demand (logging, ready/error,
status snapshots), so there is no dual tracking.

## 2. ConfigMerger Utility ✔️
`ConfigMerger` owns all map ⇄ POJO conversion, merge logic, and diff generation. The runtime uses it
for default seeding and config updates, removing the old relaxed-key helpers.

## 3. Typed Defaults Only ✔️
Every `PocketHiveWorkerProperties` implementation binds typed defaults (via `CanonicalWorkerProperties`) and seeds
the runtime with those POJOs. The previous `convertDefaultConfig`/map-based path is gone.

## 4. Control-Plane Notifier ✔️
Ready/error/log logging now lives in `ControlPlaneNotifier`, so `handleConfigUpdate` just
config-merges, updates state, and lets the notifier publish confirmations + logs.

## 5. Delete Legacy Helpers ✔️
`WorkerControlPlaneRuntime` no longer carries relaxed-key maps or logging helpers; raw maps are
serialized via `ConfigMerger` only when needed. The runtime now exclusively manages typed config
state, so this cleanup step is complete.

These steps will leave the runtime lean and make config flows predictable, while still letting
framework code emit the necessary control-plane confirmations and status updates.
