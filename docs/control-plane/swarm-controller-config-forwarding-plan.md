# Swarm Controller — Config Update Forwarding Plan

Purpose: stop double‐sending `config-update` signals from the swarm controller when the orchestrator already targeted a specific worker, while preserving swarm-level fan-outs.

## Current behaviour (baseline)
1. Orchestrator REST endpoint `/api/components/{role}/{instance}/config` emits `sig.config-update.<swarm>.<role>.<instance>`.
2. `SwarmSignalListener.shouldProcessConfigUpdate(...)` returns `true` even when the routing key role is not the controller, as long as `commandTarget` is `INSTANCE`/`ROLE`.
3. `processConfigUpdate(...)` adds `forwardToInstance(...)` / `forwardToRole(...)` tasks for every non-self signal (lines ~330–380), so bees receive both the orchestrator signal and the controller’s duplicate (`origin=<controllerInstance>`).
4. For `commandTarget=SWARM`, the fan-out to `.ALL.ALL` is intentional and documented (§4.3.1 of `docs/ORCHESTRATOR-REST.md`).

## Target outcome
- Component-level toggles (INSTANCE/ROLE) are handled solely by the orchestrator.
- Swarm-level toggles (SWARM/ALL) continue to pass through the controller and fan out to bees.
- Avoid potential loops when someone publishes `sig.config-update.<swarm>.ALL.ALL` directly.

## Implementation steps
1. **Guard config-update routing in `SwarmSignalListener`:**
   - Update `shouldProcessConfigUpdate(...)` to return `false` when `key.role()` differs from the controller role *and* `commandTarget` is `INSTANCE` or `ROLE`.
   - Keep accepting signals where `commandTarget` is `SWARM` or `ALL` (controller needs to manage those).
   - File: `swarm-controller-service/src/main/java/io/pockethive/swarmcontroller/SwarmSignalListener.java`.
2. **Remove unnecessary fan-outs:**
   - In `processConfigUpdate(...)`, only enqueue `forwardToInstance/forwardToRole/forwardToAll` when `commandTarget` is `SWARM` or `ALL`.
   - Skip re-sending for worker-scoped signals; instead, just record controller state if needed and emit `ready/error`.
3. **Add regression coverage:**
   - Extend `SwarmSignalListenerTest` to assert that `forwardToInstance` is not invoked for `commandTarget=INSTANCE` when the incoming routing key already points to that worker.
   - Add a test ensuring `commandTarget=SWARM` still produces the fan-out.
4. **Improve diagnostics:**
   - Emit a single DEBUG log per incoming config-update that includes the full signal payload (including `origin`/sender) so we can trace who published it. If the control-plane envelope currently drops the sender, add it to the log output or extend the signal model with an explicit `sender` field.
5. **Docs & release note:**
   - Update `CHANGELOG.md` under “Swarm Controller” to mention that component-level config toggles are no longer duplicated.
   - Optional: mention the clarification in `docs/ORCHESTRATOR-REST.md` §4.1/§4.3.

## Risks / considerations
- Ensure existing swarm pause/resume flows still receive the ready/error confirmations (watch `pendingStart/pendingTemplate` interactions).
- Verify controller metrics/status updates (worker status scheduler) continue to reflect enablement after the change.

## Success criteria
- Toggling a component in the UI logs exactly one `config-update` per worker (plus the swarm-level one when applicable).
- No regression in swarm enable/disable fan-outs.
