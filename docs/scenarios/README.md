# PocketHive Scenarios — Overview

This section is the **single source of truth** for PocketHive scenarios:
how they are structured, how they map to Swarm plans and worker config,
and how to author, validate, and evolve them.

It is intentionally **reference‑first** and geared towards both humans
and AI tooling.

## What is a Scenario?

A **Scenario** describes a reusable swarm topology plus per‑role
configuration, suitable for different environments (local, CI, remote).

On the wire it is a YAML document matching `io.pockethive.scenarios.Scenario`
and `io.pockethive.swarm.model.SwarmTemplate`:

- Top‑level metadata (`id`, `name`, `description`).
- A `template` section that maps 1:1 to a `SwarmTemplate`:
  - `image` – swarm‑controller image name (e.g. `swarm-controller:latest`).
  - `bees[]` – list of worker definitions (`Bee` records).
- Optional `trafficPolicy` – buffer guards and other traffic shaping.

Scenarios are stored as bundles under
`scenarios/bundles/{scenarioId}/scenario.yaml` at the repo root and are
loaded by the Scenario Manager service at startup. The authoring root is
configurable via the `scenarios.dir` property; bundles are then expected
under `scenarios.dir/bundles/{scenarioId}`.

## Key building blocks

- **Bee** (`common/swarm-model/src/main/java/io/pockethive/swarm/model/Bee.java`)
  - `role` – logical role name (e.g. `generator`, `processor`).
  - `image` – container image for this role (e.g. `generator:latest`).
  - `work` – inbound/outbound queue suffixes (`in`, `out`).
  - `env` – optional raw environment variables.
  - `config` – arbitrary per‑role configuration map; this is what is
    turned into worker config‑updates.
- **Work** (`work` inside each bee)
  - `in` – inbound queue suffix (e.g. `genQ`).
  - `out` – outbound queue suffix (e.g. `modQ`).
  - Swarm Controller expands these into full queue names using the swarm id.
- **Worker config** (`config.worker`, `config.inputs`, `config.outputs`)
  - Mirrors the configuration structure expected by worker services,
    including IO configuration such as `inputs.scheduler.ratePerSec`
    or `inputs.redis.listName`.
- **Docker config** (`config.docker.volumes`)
  - Optional list of volume bindings for this bee (`host:container[:mode]`).

The Scenario Manager converts a `Scenario` into a `SwarmPlan` that the
Orchestrator and Swarm Controller understand. The `SwarmPlan` is the
control‑plane contract; scenarios are the authoring‑time format.

## Files in this section

- `SCENARIO_CONTRACT.md` – exact YAML contract for scenarios and the
  mapping to `SwarmTemplate`/`SwarmPlan`.
- `SCENARIO_PATTERNS.md` – worked examples:
  simple REST swarm, templated REST, Redis dataset demo, and guarded
  scenarios.
- `SCENARIO_TEMPLATING.md` – how to use Pebble + SpEL helpers,
  HTTP Builder templates, and the scenario‑templating CLI tool.
- `SCENARIO_PLAN_GUIDE.md` – user guide for the `plan` section and the
  Scenario Plan editor in Hive.
  - `local-rest-schema-demo/` – example of a generator that uses a JSON Schema
    (`schemas/local-rest-body.schema.json`) to help author HTTP bodies in the
    scenario editor while still emitting a plain templated JSON string at runtime.

These docs are designed to be embedded into the UI as help pages.
Callers should link here rather than re‑describing the contract.

Status tracking:
- `SCENARIO_EDITOR_STATUS.md` – what the Scenarios editor does today vs what is missing/buggy.

---

## Scenario bundles and the Scenarios UI

In the repo (and on disk in deployments) scenarios are stored as
**bundles**:

```text
scenarios/
  bundles/
    local-rest-plan-demo/
      scenario.yaml
      templates/
        http/
      sut/
      datasets/
      docs/
```

- `scenario.yaml` contains the **template** and optional **plan**.  
- `templates/` holds protocol templates. Today HTTP templates live under
  `templates/http/`; TCP/ISO/SOAP/etc can live under sibling folders as they
  are introduced.  
- `sut/` and `datasets/` are optional and may contain SUT configs or input
  data; exact conventions are described in the in‑progress
  `docs/inProgress/scenario-bundle-runtime-plan.md`.

Hive exposes these bundles on the **Scenarios** page:

- The left pane lists scenario bundles (loaded from Scenario Manager).  
- The right pane has three views of the same YAML:
  - **Plan** – visual, timeline‑based editor for the `plan` section.  
  - **Swarm template** – editor for `template.image`, `template.bees[]`,
    `work.in/out` port maps, and basic IO type selection (`config.inputs.type`).  
  - **Scenario YAML** – full text editor backed by Monaco.

YAML remains the **single source of truth**:

- Plan and swarm edits patch `scenario.yaml` in memory.  
- Saving writes the updated YAML back to the bundle via Scenario Manager.  
- Download/replace bundle operations operate on the same on‑disk structure.

For details of what the plan can express and how the visual editor maps to
YAML, see `SCENARIO_PLAN_GUIDE.md`. For IO and capabilities used by the
config dialogs, see `docs/architecture/workerCapabilities.md`.
