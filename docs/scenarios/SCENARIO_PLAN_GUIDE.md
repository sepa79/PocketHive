# Scenario Plans — User Guide (v1)

> This guide explains the **`plan`** section of a Scenario: what it can do
> today, how time‑based steps work, and how to express them in YAML.

The Scenario **template** describes *what* runs in a swarm (controller + bees).  
The **plan** describes *when and how* those bees should change behaviour over
time.

At runtime the Scenario Manager turns the `plan` into a sequence of
`config-update` commands and swarm lifecycle toggles driven by the Swarm
Controller.

---

## 1. What Plans can do today

With the current engine, a plan can:

- Start a swarm, wait for readiness, then run a **timeline of steps**.
- Change worker configuration at specific offsets (for example ramp generator
  rate from 5 → 10 → 20 msg/s).
- Enable/disable:
  - a specific worker instance, or
  - all workers with a given role, or
  - the entire swarm.
- Be **run multiple times** (looped) by the Swarm Controller; Hive shows
  progress and the next step countdown.

What it does **not** do yet:

- No conditionals or branching (`if`, `await`, error‑rate triggers).
- No dynamic scaling; plans only send `config-update` and start/stop semantics.

---

## 2. Shape of the `plan` section

The `plan` section lives alongside `template`:

```yaml
template:
  image: pockethive-swarm-controller:latest
  instanceId: marshal-bee
  # bees: [...]

plan:
  bees:   # per‑instance steps
    - instanceId: seeder-bee
      steps:
        - stepId: ramp-10
          name: Increase generator rate to 10/s
          time: PT15S
          type: config-update
          config:
            inputs:
              scheduler:
                ratePerSec: 10

  swarm:  # optional swarm‑wide steps
    - stepId: swarm-stop
      name: Stop everything after 5 minutes
      time: PT5M
      type: stop
```

### 2.1 Bee‑scoped steps (`plan.bees[]`)

Each entry under `plan.bees` targets a **single worker instance** in the swarm:

- `instanceId` must match a bee from `template.bees`.
- `steps[]` contains time‑ordered actions for that instance.

Internally:

- Steps for that `instanceId` are executed at their due times.
- If only `instanceId` is present, the engine uses `commandTarget=INSTANCE`.
- If you also specify a `role`, the engine includes it in the command.

### 2.2 Swarm‑scoped steps (`plan.swarm[]`)

`plan.swarm` allows steps that **apply to the whole swarm**:

- No `instanceId` / `role` – the engine treats them as swarm‑level.
- In v1 they are primarily used for `type: start` / `type: stop` to control
  swarm‑wide enablement.

---

## 3. Step fields

Every step has the following fields:

```yaml
stepId: ramp-10          # required, unique within the plan
name: Increase rate      # friendly label (for UI/logs)
time: PT15S              # required, ISO‑8601 duration from plan start
type: config-update      # optional, defaults to config-update
config: {...}            # optional, payload for config-update steps
```

- `stepId` must be provided and unique; it is used for idempotency.
- `time` is a duration (e.g. `PT15S`, `PT1M`, `PT1H30M`).
- `type` controls how the step is interpreted (see below).
- `config` is an arbitrary map specific to the target worker role.

Steps are **per‑instance** sorted by `time`. If you want a strict order for
multiple actions around the same moment, use slightly staggered times such as
`PT60S`, `PT60.1S`, `PT60.2S`.

---

## 4. Step types

The engine supports three step types in v1:

### 4.1 `config-update` (default)

Sends a `config-update` command to the target with the given `config` payload.

Usage:

```yaml
- stepId: ramp-10
  name: Increase generator rate to 10/s
  time: PT15S
  type: config-update       # can be omitted; this is the default
  config:
    inputs:
      scheduler:
        ratePerSec: 10
```

Semantics:

- For a bee‑scoped step, the controller emits:
  - `commandTarget=INSTANCE` (or `ROLE` when only `role` is provided).
  - `args.data` equal to the `config` map.
- Workers treat it as any other runtime config update; there is no extra magic
  based on the step id.

You can use `config-update` to:

- Change generator/trigger rates.
- Toggle buffer guards or other manager‑side helpers.
- Adjust HTTP builder settings (service ids, template roots, etc.).

### 4.2 `start`

Enables work at the chosen scope.

```yaml
# Swarm‑wide
- stepId: swarm-start
  name: Enable all workers
  time: PT0S
  type: start

# Single instance
- stepId: start-processor
  name: Start processor worker
  time: PT60S
  type: start
  config: {}         # optional, usually omitted
```

Semantics:

- **Swarm step (`plan.swarm`)**:
  - Calls the Swarm Controller lifecycle to enable workloads across the swarm.
  - Equivalent to pressing “Start swarm” in the UI, but driven by time.
- **Bee step (`plan.bees`)**:
  - Emits a `config-update` with `enabled: true` for the target worker/role.

### 4.3 `stop`

Disables work at the chosen scope.

```yaml
# Swarm‑wide stop after 5 minutes
- stepId: swarm-stop
  name: Stop all workers
  time: PT5M
  type: stop

# Per‑instance stop
- stepId: stop-processor
  name: Stop processor worker
  time: PT60S
  type: stop
```

Semantics mirror `start`:

- **Swarm step** – drives a swarm‑wide stop via the controller.
- **Bee step** – emits `enabled: false` for the target worker/role.

Any other `type` values are ignored in v1.

---

## 5. Putting it together — examples

### 5.1 Generator ramp‑up

Goal: start at 5 msg/s, ramp to 10 msg/s at 15s, then to 20 msg/s at 30s.

```yaml
plan:
  bees:
    - instanceId: seeder-bee
      steps:
        - stepId: ramp-10
          name: Increase generator rate to 10/s
          time: PT15S
          type: config-update
          config:
            inputs:
              scheduler:
                ratePerSec: 10

        - stepId: ramp-20
          name: Increase generator rate to 20/s
          time: PT30S
          type: config-update
          config:
            inputs:
              scheduler:
                ratePerSec: 20
```

### 5.2 Swarm on/off window

Goal: run a test swarm for exactly 5 minutes and stop all workers.

```yaml
plan:
  swarm:
    - stepId: swarm-start
      name: Enable all workers
      time: PT0S
      type: start

    - stepId: swarm-stop
      name: Stop all workers
      time: PT5M
      type: stop
```

### 5.3 Coordinated SUT change

Goal: stop traffic, reconfigure the SUT (via a moderator/HTTP builder), and
then resume processing.

```yaml
plan:
  bees:
    - instanceId: worker-bee
      steps:
        - stepId: stop-processor
          name: Stop processor
          time: PT60S
          type: stop

    - instanceId: guardian-bee
      steps:
        - stepId: reconfig-sut
          name: Apply new WireMock stubs
          time: PT60.1S
          type: config-update
          config:
            worker:
              baseUrl: "{{ sut.endpoints['wiremock-new'].baseUrl }}"

    - instanceId: worker-bee
      steps:
        - stepId: start-processor
          name: Start processor
          time: PT60.2S
          type: start
```

---

## 6. Observing and controlling runs

When a plan is loaded, the Swarm Controller:

- Publishes scenario progress under `data.scenario` in its status payload:
  - `lastStepId`, `lastStepName`
  - `nextStepId`, `nextStepName`, `nextDueMillis`
  - `elapsedMillis`
  - optional `totalRuns` / `runsRemaining` when looping is configured.
- Exposes this in Hive:
  - Swarm Controller detail drawer in the Hive view.
  - Swarms list view’s “Scenario” column.

The controller also accepts `config-update` commands to:

- Reset the plan (restart from the first step).
- Set how many times the plan should execute (run count).

The Hive UI’s Swarm Controller panel and Swarms view wrap these controls in a
small “scenario controls” section so you can operate plans without crafting
raw control‑plane messages.***
