# In‑Progress Plans

This directory groups **active architectural and SDK plans** that are
currently being implemented or iterated on. Some live directly under
`docs/inProgress`, others sit alongside the relevant code (for example
under `docs/sdk`, `docs/control-plane`, or `docs/architecture`). This
index is a convenience for humans and tools.

Notable in‑progress plans:

- `docs/architecture/ScenarioPlan.md` – Scenario engine and environment/SUT profiles.
- `docs/architecture/manager-sdk-plan.md` – Manager SDK and Swarm Controller reuse.
- `docs/control-plane/worker-config-propagation-plan.md` – Scenario → SwarmPlan → worker config propagation.
- `docs/control-plane/queue-guard-plan.md` – BufferGuard and queue guard behaviour.
- `docs/sdk/worker-configurable-io-plan.md` – Configurable worker IO types and factories.
- `docs/sdk/config-key-normalisation-plan.md` – Normalising config key shapes.
- `docs/inProgress/scenario-bundle-runtime-plan.md` – Directory-based scenario bundles and per-swarm runtimes.
- `docs/inProgress/scenario-sut-editor-plan.md` – Scenario + SUT editors (Scenario editor notes superseded by `docs/scenarios/SCENARIO_EDITOR_STATUS.md`).
- `docs/inProgress/redis-loop-io-optimization-plan.md` – Redis multi-list input, weighted pick, and dynamic uploader routing for loop scenarios.

Each of those files carries a `Status: in progress` header so it’s clear
they are live design documents rather than historical notes.
