# Manual Redis source-list change — design and plan

> Status: **implemented; targeted verification complete**.

## 1. Goal

Allow an operator to manually change `inputs.redis.listName` through the existing Hive runtime config editor without rematerializing the swarm.

The intended workflow is:

1. Stop the swarm.
2. Open the existing runtime config editor for the Redis dataset worker.
3. Manually enter another `inputs.redis.listName`.
4. Save and wait for the correlated config-update result.
5. Start the same swarm again.

## 2. Scope

Only `inputs.redis.listName` becomes runtime-configurable.

Out of scope:

- no Redis list discovery;
- no Redis Commander integration;
- no new REST endpoint or control-plane signal;
- no new UI component, selector, combobox, or special Redis form;
- no Redis output/destination-list editing;
- no mutation of host, port, credentials, TLS, `sources[]`, or `pickStrategy`;
- no lifecycle or Swarm Controller changes;
- no conversion between single-source and multi-source modes.

The existing capability-driven config form remains the only UI.

## 3. Design

### 3.1 Capability

Mark the existing field as available to the generic runtime editor:

```yaml
- name: inputs.redis.listName
  type: string
  liveMutable: true
  required: true
  allowBlank: true
  ui:
    label: Redis list (single source)
    group: Redis
    help: "Change only while the swarm is stopped. Enter the Redis list name manually."
```

The existing modal and generic string control are reused. A small submission
guard blocks a patch containing this field unless the selected swarm status is
explicitly `STOPPED`; no Redis-specific form or selector is added.

`allowBlank` remains `true` because the same capability also describes valid multi-source configurations where `listName` is empty and `sources[]` is populated. The disabled-only runtime guard, not the manifest's authoring constraint, rejects a blank list-selection patch.

The capability version must be bumped because this is a user-visible change.

### 3.2 Authoritative safety rule

Capability metadata controls presentation only. The worker must enforce the mutation rule.

`LiveIoConfigUpdateGuard` should accept a changed `inputs.redis.listName` only when all conditions are true:

- target input type is `REDIS_DATASET`;
- target worker currently has `enabled == false`;
- current configuration is already single-source mode;
- `inputs.redis.sources` is empty;
- the new `listName` is non-blank;
- the sparse patch changes no other unsafe IO field.

The same patch must fail explicitly when the worker is enabled. This prevents a direct API caller from performing a live source switch even though the generic UI displays the field while the swarm is running.

Worker enablement is the race-safe execution boundary. If Start enables the worker before the config patch is validated, the patch is rejected.

### 3.3 Single-source mode remains locked

The feature changes the selected list, not the source mode.

- A worker already using `listName` may switch to another non-blank `listName`.
- A worker using `sources[]` must reject a `listName` runtime patch.
- The implementation must not silently clear `sources[]`.
- `sources[]` and `pickStrategy` remain immutable at runtime.

### 3.4 Applying the change

The existing endpoint and control-plane contract are reused unchanged:

```http
POST /api/components/{role}/{instance}/config
```

Sparse patch:

```json
{
  "inputs": {
    "redis": {
      "listName": "cards.TOP"
    }
  }
}
```

The existing `config-update` correlation, idempotency, outcome, alert, and status mechanisms remain authoritative. No second mutation path is introduced.

The existing `RedisDataSetWorkInput` update handling already applies a changed `listName`; implementation must preserve the existing Redis connection settings and reset only list-selection state required for the new source.

### 3.5 UI behaviour

The existing generic editor displays the field in both running and stopped
views, but blocks submission of a patch containing `inputs.redis.listName`
unless the selected swarm status is explicitly `STOPPED`. Other live-mutable
fields remain editable while running.

Safety does not depend solely on the form state:

- while stopped/disabled, the patch succeeds;
- while running/enabled, the UI blocks submission and the worker rejects any
  request that bypasses or races the UI;
- the help text tells the operator to stop the swarm first.

Agents using PocketHive MCP must call `swarm_get` and verify `STOPPED` before
sending this field. An accepted Stop request is not sufficient evidence, and
agents must not infer completion.

## 4. Required changes

### Contract and documentation

- Update `docs/ARCHITECTURE.md` so `inputs.redis.listName` is the only source-selection field allowed to change while its worker is disabled.
- Update `docs/architecture/workerCapabilities.md` to document that this entry is exposed by the generic runtime editor but enforced as disabled-only by the worker.
- Do not add a REST endpoint, signal, routing key, DTO, or response schema.

### Scenario validation contracts

- Add `inputs.redis.listName` to the explicit runtime-mutability policy.
- Distinguish fields that are safe while enabled from `listName`, which is safe only while disabled.
- Keep all other Redis wiring paths immutable.

### Scenario Manager capability

- Set `inputs.redis.listName.liveMutable: true`.
- Keep `allowBlank: true` for valid multi-source authoring.
- Add clear stopped-first help text.
- Bump the Redis dataset capability version.
- Update manifest validation tests for the explicit exception.

### Worker SDK

- Pass the existing `previousEnabled` state into IO mutability validation.
- Permit only a `listName`-only patch when disabled and in single-source mode.
- Reject it when enabled or when `sources[]` is active.
- Ensure rejection is atomic and emits the existing structured config-update failure.
- Keep using the existing config merger, listener notification, outcome, and status snapshot paths.

### UI v2

- Reuse the existing capability composition and generic string control.
- Block only patches containing `inputs.redis.listName` unless the selected
  swarm status is explicitly `STOPPED`; keep other live tuning available.

### Orchestrator and Swarm Controller

- No code changes.
- Existing exact-instance config endpoint and routing are reused.

## 5. Test plan

### Unit

- Capability manifest exposes `inputs.redis.listName` as `liveMutable: true` without breaking multi-source authoring.
- Runtime mutability validation accepts a changed `listName` when the worker is disabled.
- The same patch is rejected when the worker is enabled.
- A disabled multi-source worker rejects the patch without clearing `sources[]`.
- A mixed patch containing `listName` plus another unsafe IO field is rejected atomically.
- Blank and null list names are rejected.
- Existing `inputs.redis.ratePerSec` behaviour is unchanged.

### Integration

- A stopped worker accepts the existing exact-instance config-update and reports the new list in its status config.
- A running worker returns a correlated structured failure and keeps the previous list.
- Stop followed by list change followed by Start consumes from the new list without rematerialization.
- A Start/config-update race cannot apply the source change after the worker becomes enabled.

### E2E through official UI ingress

1. Start a Redis dataset swarm and verify the current source.
2. Stop the swarm and wait for worker `enabled=false`.
3. Open the existing runtime config modal.
4. Manually enter a different `listName` and submit.
5. Verify the correlated config-update succeeds and status reports the new name.
6. Start the same swarm without redeployment.
7. Verify data is consumed from the new list.
8. Attempt the same edit while running and verify an explicit failure with no source change.

## 6. Acceptance criteria

- The existing runtime config editor shows a manual text field for `inputs.redis.listName`.
- No Redis-specific UI component or discovery service is added.
- A disabled single-source Redis worker accepts a non-blank `listName` change.
- An enabled worker rejects the same change.
- Multi-source mode is not modified or converted implicitly.
- No other Redis wiring field becomes runtime-mutable.
- The change survives Stop → config-update → Start without rematerializing the swarm.
- Existing correlation, idempotency, outcomes, alerts, and status config are reused.
- No lifecycle state-machine code is changed.

## 7. Delivery plan

- [x] Phase 1 — update architecture and capability mutability documentation.
- [x] Phase 2 — extend the shared IO mutability policy and worker guard.
- [x] Phase 3 — update the Redis dataset capability manifest.
- [x] Phase 4 — add unit and integration coverage.
- [ ] Phase 5 — run the official-ingress E2E and update the changelog.

## 8. Review

Review performed against the PocketHive non-negotiable rules and `docs/ai/REVIEW_CHECKLIST.md`.

### Resolved findings

1. **P1 — capability-only change would still be rejected by the worker.** The plan includes the narrow disabled-only worker guard change.
2. **P1 — `liveMutable: true` exposes the field while running.** This is accepted as a UX limitation; backend enforcement prevents the unsafe mutation and help text gives the stopped-first workflow.
3. **P1 — existing Redis input code can clear `sources[]` when `listName` changes.** The plan explicitly rejects runtime changes in multi-source mode instead.
4. **P1 — STOP/START race could create a live source switch.** The worker checks its authoritative enabled state immediately before applying the atomic patch.
5. **P2 — a Redis discovery endpoint and custom UI would exceed the requested scope.** Both are removed.
6. **P2 — a second action/signal contract would violate KISS for manual entry.** Existing config-update remains the only mutation contract.
7. **P2 — Swarm Controller changes are unnecessary.** Exact worker config routing is reused unchanged.
8. **P1 — setting `allowBlank: false` would invalidate multi-source authoring.** The manifest keeps `allowBlank: true`; the worker rejects blank runtime selection patches.

### Remaining implementation review gates

- Verify the guard evaluates current source mode without mutating it.
- Verify the new list remains effective after the subsequent Start.
- Verify config-update failure is human-readable and structured using existing contracts.
- Verify capability validation documents this one disabled-only exception without weakening other IO wiring rules.
- Verify UI changes are limited to the generic stopped-state submission guard
  and no Orchestrator, Swarm Controller, routing, or lifecycle files change.
