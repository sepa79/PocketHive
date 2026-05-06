# Scenario Plan Guide

The `plan` section of a `scenario.yaml` defines a **time-based automation timeline**
that runs inside the Swarm Controller after the swarm starts. It lets you change worker
behaviour, ramp rates, and stop the swarm automatically — all driven by elapsed time,
without any external intervention.

> Authoritative source: `docs/pockethive-ref/scenarios/SCENARIO_PLAN_GUIDE.md`

---

## What a plan can do

- Ramp generator/moderator rates at specific time offsets
- Enable or disable individual workers or the whole swarm on a schedule
- Run the timeline multiple times (looping)
- Expose progress in the Hive UI (next step, elapsed time, runs remaining)

What it **cannot** do (v1):
- No conditionals or branching
- No dynamic scaling based on metrics or error rates

---

## Structure

`plan` sits alongside `template` at the top level of `scenario.yaml`:

```yaml
id: my-scenario
name: My Scenario
template:
  image: swarm-controller:latest
  bees:
    - role: generator
      image: generator:latest
      # instanceId is used by plan.bees to target this worker
      # if omitted, the swarm controller assigns one automatically
      work:
        out:
          out: build
      config:
        inputs:
          type: SCHEDULER
          scheduler:
            ratePerSec: 5
            maxMessages: 0
    # ... other bees

plan:
  bees:    # per-instance steps
    - instanceId: seeder-bee
      steps:
        - stepId: ramp-10
          name: Increase rate to 10/s
          time: PT15S
          type: config-update
          config:
            inputs:
              scheduler:
                ratePerSec: 10

  swarm:   # swarm-wide steps (optional)
    - stepId: swarm-stop
      name: Stop after 5 minutes
      time: PT5M
      type: stop
```

---

## plan.bees — per-instance steps

Each entry targets a **single worker instance** by `instanceId`:

```yaml
plan:
  bees:
    - instanceId: seeder-bee      # must match a bee's role or assigned instance name
      steps:
        - stepId: ramp-10
          time: PT15S
          type: config-update
          config:
            inputs:
              scheduler:
                ratePerSec: 10
```

- `instanceId` matches the bee's role name (e.g. `generator`, `seeder-bee`) or the
  instance name assigned by the controller
- Multiple bees can each have their own step list
- Steps within one bee are sorted by `time` and executed in order

---

## plan.swarm — swarm-wide steps

Steps that apply to the entire swarm — no `instanceId` needed:

```yaml
plan:
  swarm:
    - stepId: swarm-start
      time: PT0S
      type: start

    - stepId: swarm-stop
      time: PT5M
      type: stop
```

---

## Step fields

| Field | Required | Description |
|---|---|---|
| `stepId` | yes | Unique within the plan. Used for idempotency. |
| `name` | no | Human-readable label shown in UI and logs |
| `time` | yes | ISO-8601 duration from plan start (e.g. `PT15S`, `PT1M30S`, `PT1H`) |
| `type` | no | `config-update` (default), `start`, or `stop` |
| `config` | no | Arbitrary config map for `config-update` steps |

For simultaneous actions, stagger times slightly: `PT60S`, `PT60.1S`, `PT60.2S`.

---

## Step types

### `config-update` (default)

Sends a config-update to the target worker. The `config` map is the patch — only
include fields you want to change.

```yaml
- stepId: ramp-10
  time: PT15S
  type: config-update       # optional — this is the default
  config:
    inputs:
      scheduler:
        ratePerSec: 10
```

Use it to:
- Change generator/moderator rates
- Toggle buffer guards
- Adjust any worker config field at runtime

### `start`

Enables work at the target scope.

```yaml
# Swarm-wide start
- stepId: swarm-start
  time: PT0S
  type: start

# Single worker start
- stepId: start-processor
  time: PT60S
  type: start
```

- In `plan.swarm` — equivalent to pressing "Start swarm"; enables all workers
- In `plan.bees` — sends `enabled: true` to that specific worker

### `stop`

Disables work at the target scope.

```yaml
# Swarm-wide stop after 5 minutes
- stepId: swarm-stop
  time: PT5M
  type: stop

# Single worker stop
- stepId: stop-generator
  time: PT30S
  type: stop
```

- In `plan.swarm` — stops the whole swarm
- In `plan.bees` — sends `enabled: false` to that specific worker

---

## Complete examples

### Generator ramp-up (5 → 10 → 20 msg/s)

```yaml
plan:
  bees:
    - instanceId: generator
      steps:
        - stepId: ramp-10
          name: Ramp to 10/s at 15s
          time: PT15S
          type: config-update
          config:
            inputs:
              scheduler:
                ratePerSec: 10

        - stepId: ramp-20
          name: Ramp to 20/s at 30s
          time: PT30S
          type: config-update
          config:
            inputs:
              scheduler:
                ratePerSec: 20
```

### Fixed-duration test (auto-stop after 5 minutes)

```yaml
plan:
  swarm:
    - stepId: swarm-stop
      name: Stop after 5 minutes
      time: PT5M
      type: stop
```

### Full ramp-up + soak + stop

```yaml
plan:
  bees:
    - instanceId: generator
      steps:
        - stepId: ramp-mid
          name: Ramp to 50/s
          time: PT1M
          type: config-update
          config:
            inputs:
              scheduler:
                ratePerSec: 50

        - stepId: ramp-peak
          name: Ramp to 100/s
          time: PT2M
          type: config-update
          config:
            inputs:
              scheduler:
                ratePerSec: 100

  swarm:
    - stepId: swarm-stop
      name: Stop after 5 minute soak
      time: PT7M
      type: stop
```

### Coordinated stop → reconfigure → restart

Stop a worker, apply a config change, then restart — all timed:

```yaml
plan:
  bees:
    - instanceId: processor
      steps:
        - stepId: stop-proc
          time: PT60S
          type: stop

        - stepId: reconfig-proc
          time: PT60.1S
          type: config-update
          config:
            worker:
              threadCount: 20

        - stepId: start-proc
          time: PT60.2S
          type: start
```

---

## Observing plan progress

The Swarm Controller publishes plan state in its `status-full` payload under
`data.scenario`:

```json
{
  "firedStepIds": ["ramp-10"],
  "elapsedMillis": 18500,
  "nextStepId": "ramp-20",
  "nextStepName": "Ramp to 20/s at 30s",
  "nextDueMillis": 11500
}
```

Check this via `swarm.get` — look at `envelope.data.context.scenario`.

Also visible in `debug.journal` as `scenario-plan-loaded` and step-fired events.

---

## Relationship to `instanceId` in bees

The `instanceId` in `plan.bees` must match the **role name** of the bee as defined
in `template.bees`. The Swarm Controller resolves it to the running instance.

If you have multiple bees with the same role (e.g. two generators), you need to
assign explicit `id` fields on the bees and use those in the plan:

```yaml
template:
  bees:
    - role: generator
      id: gen-a          # explicit id for plan targeting
      image: generator:latest
      work:
        out:
          out: build-a
      config: { ... }

    - role: generator
      id: gen-b
      image: generator:latest
      work:
        out:
          out: build-b
      config: { ... }

plan:
  bees:
    - instanceId: gen-a
      steps:
        - stepId: ramp-gen-a
          time: PT30S
          type: config-update
          config:
            inputs:
              scheduler:
                ratePerSec: 20

    - instanceId: gen-b
      steps:
        - stepId: ramp-gen-b
          time: PT60S
          type: config-update
          config:
            inputs:
              scheduler:
                ratePerSec: 50
```

---

## When to use plan vs manual config-update

| Situation | Use |
|---|---|
| Automated ramp-up on a fixed schedule | `plan` |
| Fixed-duration test with auto-stop | `plan` with `swarm` stop step |
| Ad-hoc rate change during a running test | `debug.config-update` MCP tool |
| Conditional changes based on metrics | Not supported in v1 — use manual intervention |
| Repeating the same ramp pattern | `plan` with looping (configured via Hive UI or `config-update` to controller) |
