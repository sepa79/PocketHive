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

Scenarios are stored as bundles under `scenarios/**/<scenarioId>/scenario.yaml`
at the repo root and are loaded by the Scenario Manager service at startup.
The authoring root is configurable via the `scenarios.dir` property; bundles
may live anywhere under that root (subfolders are supported).

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
- **Worker config** (`config`, `config.inputs`, `config.outputs`)
  - Mirrors the configuration structure expected by worker services,
    including IO configuration such as `inputs.scheduler.ratePerSec`
    or `inputs.redis.listName` / `inputs.redis.sources`, and output
    configuration such as `outputs.redis.*` when `outputs.type=REDIS`.
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
- `docs/guides/templating-basics.md` – Pebble + `eval(...)` baseline usage.
- `docs/guides/templating-advanced.md` – Redis sequences, advanced patterns, and validation workflow.
- `SCENARIO_TEMPLATING.md` – compatibility entry that redirects to the guides.
- `SCENARIO_VARIABLES.md` – Scenario Variables (`variables.yaml`) and how `vars.*` is injected into templates.
- `SCENARIO_PLAN_GUIDE.md` – user guide for the `plan` section and the
  Scenario Plan editor in Hive.
  - `local-rest-schema-demo/` – example of a generator that uses a JSON Schema
    (`schemas/local-rest-body.schema.json`) to help author HTTP bodies in the
    scenario editor while still emitting a plain templated JSON string at runtime.
- `SCENARIO_BUNDLE_DIAGNOSTICS.md` – contract for how Scenario Manager exposes
  healthy and defunct bundle entries to the UI.

These docs are designed to be embedded into the UI as help pages.
Callers should link here rather than re‑describing the contract.

Status tracking:
- `SCENARIO_EDITOR_STATUS.md` – what the Scenarios editor does today vs what is missing/buggy.
- `SCENARIO_BUNDLE_WORKSPACE_API_SPEC.md` – generic bundle tree/file API for the UI v2 workspace.

---

## Scenario bundles and the Scenarios UI

Scenario bundles and Managed Dataset packages are separate authoring units:

```text
scenarios/
├── bundles/<scenarioId>/
│   ├── scenario.yaml
│   └── datasets/requirements.yaml  # scenario-specific requirements only
└── managed-datasets/<datasetPackageId>/
    ├── dataset.yaml                # Dataset definition SSOT
    ├── schema/
    ├── contracts/
    ├── mappings/
    ├── projections/
    ├── policies/
    ├── sources/
    └── assets/
```

The two Dataset locations have different ownership. A scenario bundle may
declare only its own requirements and scenario-owned assets. It must not copy a
Dataset definition. The standalone Dataset package owns its record schema,
package-local field-subset contracts, storage requirements/supported profiles,
mappings, projections, policies and sources. A deployment-scoped registration
owns Dataset Space, alias and explicit adapter/settings/profile. See
[`scenarios/managed-datasets/README.md`](../../scenarios/managed-datasets/README.md).
The production authoring UI lists and reads these objects only through
authorised Scenario Manager APIs. Add/edit/remove controls execute the same
versioned application commands as MCP: drafts may be deleted only when
unreferenced, while published/active package, Space and registration versions
are replaced by a new version or retired rather than hard-deleted. No example
authoring objects or successful command results ship in the UI bundle.

In the repo (and on disk in deployments) scenarios are stored as
**bundles**:

```text
scenarios/
  <group-or-folder>/              # optional (e.g. tcp/, e2e/, demos/)
    <scenarioId>/
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
- `sut/` and `datasets/` are optional. A bundle's `datasets/` may contain the
  reserved `requirements.yaml` and scenario-owned input assets, but never a
  standalone Dataset definition; exact conventions are described in the in-progress
  `docs/archive/scenario-bundle-runtime-plan.md`.

Example bundles in this repo:
- `scenarios/e2e/variables-demo/` – demonstrates `variables.yaml` + `vars.*` + `eval(...)`.

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
