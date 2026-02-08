# Your First Scenario

Goal: copy an existing minimal scenario, change one thing, validate templates,
and run it.

## 1. Copy a baseline scenario bundle

Scenarios live under `scenarios/**/<scenarioId>/scenario.yaml`.

Start from:

- `scenarios/e2e/local-rest/scenario.yaml`

Copy it into a new bundle folder, for example:

```text
scenarios/tutorial/my-first-scenario/
  scenario.yaml
```

## 2. Change something small

Easy first change: reduce generator rate.

In `scenario.yaml`, edit:

- `template.bees[].role: generator`
- `config.inputs.scheduler.ratePerSec`

## 3. Validate templating (no swarm required)

```bash
tools/scenario-templating-check/run.sh --scenario scenarios/tutorial/my-first-scenario/scenario.yaml
```

If you use request templates (serviceId/callId + templateRoot), also run:

```bash
tools/scenario-templating-check/run.sh --check-http-templates --scenario scenarios/tutorial/my-first-scenario/scenario.yaml
```

## 4. Reload scenarios in Scenario Manager

If the stack is already running, you do not need a full rebuild.

Use the helper:

```bash
./build-hive.sh --sync-scenarios
```

Then create a swarm from the new scenario and start it (UI or CLI).

## 5. Troubleshooting checklist

- Scenario not visible in UI: ensure Scenario Manager is running and reloaded.
- Swarm creates but does not run: inspect `GET /api/swarms/{swarmId}` and the journal.

References:

- `docs/scenarios/SCENARIO_CONTRACT.md`
- `docs/scenarios/SCENARIO_MANAGER_BUNDLE_REST.md`
- `docs/ORCHESTRATOR-REST.md`

