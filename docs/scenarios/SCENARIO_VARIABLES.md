# Scenario Variables (Single-File Profiles + SUT Matrix)

This document proposes an authoring-time convention for **Scenario Variables** stored inside a **scenario bundle**.

Goal: parameterize one scenario (topology + config) with a selected **variables profile** and a selected **SUT** at swarm creation time.

Non-goals:
- Introducing fallback chains or cascading defaults.
- Adding a generic “execute Pebble everywhere” engine at create time.

---

## 1) Concept

Scenario Variables are named values with:

- a **scope**: `global` or `sut`
- a **type**: `string | int | float | bool`

At swarm creation time the user selects:

- `templateId` (scenario id)
- optional `sutId`
- optional `variablesProfileId` (e.g. `france`, `romania`)

Resolution rules:

- `global` variables are resolved from the selected **profile**.
- `sut` variables are resolved from **(sutId + profile)**, because the same SUT-scoped variable may differ across profiles.
- If a scenario uses variables, missing required values are **hard errors** (no implicit defaults).

---

## 2) Bundle layout

Recommended structure inside `scenarios/bundles/<scenarioId>/`:

```text
scenarios/bundles/<scenarioId>/
  scenario.yaml
  variables.yaml
  sut/
    sut-A/
      sut.yaml
    sut-B/
      sut.yaml
```

Notes:

- `variables.yaml` is optional. If it does not exist, the scenario does not use variables.
- `sut/` is the bundle-local SSOT for SUT definitions used by this scenario.
- The variables system stores the **profile × SUT** matrix in a single file to minimize maintenance drift.

---

## 3) Contracts (files)

### 3.1 `variables.yaml` (single SSOT)

`variables.yaml` defines:

- variable **definitions** (name/scope/type)
- available **profiles**
- **values**:
  - global values per profile
  - SUT-scoped values per (profile, sutId)

```yaml
version: 1
definitions:
  - name: loopCount
    scope: global
    type: int
    required: true

  - name: customerId
    scope: sut
    type: string
    required: true

  - name: enableFoo
    scope: global
    type: bool
    required: false

  - name: discount
    scope: sut
    type: float
    required: false

profiles:
  - id: france
    name: France
  - id: romania
    name: Romania

values:
  global:
    france:
      loopCount: 10
      enableFoo: true
    romania:
      loopCount: 20
      enableFoo: false

  # SUT-scoped values depend on BOTH sutId and profileId.
  sut:
    france:
      sut-A:
        customerId: "123"
        discount: 0.15
      sut-B:
        customerId: "765"
        discount: 0.20
    romania:
      sut-A:
        customerId: "456"
        discount: 0.05
      sut-B:
        customerId: "999"
        discount: 0.10
```

Rules:
- Definition names are unique.
- `scope` is one of `global|sut`.
- `type` is one of `string|int|float|bool`.
- `profiles[*].id` values are unique.
- `values.global` keys must be profile ids.
- `values.sut` keys must be profile ids, and then `sutId` values that exist in `sut/`.

---

## 4) Resolution & validation

At swarm creation time:

1) Load `variables.yaml` (if present).
2) Determine whether any variables are declared at all:
   - if `variables.yaml` is absent, `vars` does not exist and variable resolution is skipped.
3) If any `global` variables exist:
   - require `variablesProfileId`
   - use `values.global[variablesProfileId]`
4) If any `sut` variables exist:
   - require `sutId`
   - require `variablesProfileId`
   - use `values.sut[variablesProfileId][sutId]`
5) Type-check each provided value against `definitions`.
6) Ensure every declared `required: true` variable has a value for the selected inputs:
   - missing required value => hard error
7) Reject unknown keys in value maps:
   - any value key not present in `definitions` => hard error

Merge result into a single map `vars`:

- `vars` contains both global and sut variables (a “flat” namespace).
- Name collisions are rejected (they should not happen if `definitions` is the SSOT).

---

## 5) Using variables in Pebble and SpEL

### 5.1 Pebble (e.g. inside `scenario.yaml` config)

Any string field that is rendered through Pebble can reference:

- `vars.<name>`

Example:

```yaml
config:
  worker:
    customerId: "{{ vars.customerId }}"
    loopCount: "{{ vars.loopCount }}"
    enabled: "{{ vars.enableFoo }}"
    discount: "{{ vars.discount }}"
```

### 5.2 SpEL (`eval(...)`)

Inside `eval(...)`, `vars` is available as a root variable.

Examples:

```yaml
headers:
  x-loop-plus-one: "{{ eval(\"vars.loopCount + 1\") }}"
  x-flag: "{{ eval(\"vars.enableFoo ? 'Y' : 'N'\") }}"
```

Type intent:
- `int` and `float` are numeric in SpEL expressions.
- `bool` is boolean in SpEL expressions.
- `string` is a string.

If a variable is missing, rendering fails fast (hard error).

---

## 6) UI / create swarm behavior (high level)

When a bundle contains `variables.yaml`, the UI should:

- list available `variablesProfileId` values from `profiles[*].id`
- on “Create swarm”, include the selected `variablesProfileId` in the request
- if `sut`-scoped variables exist, require a `sutId` selection and validate coverage:
  - for the chosen `(sutId, profileId)`, the entry `values.sut[profileId][sutId]` must exist (and be type-valid)

If the bundle has no `variables.yaml`, the UI hides the profile selector and no extra field is sent.

Editor validation intent:

- Hard errors (block save / block create):
  - unknown value keys not present in `definitions`
  - type mismatch (`string|int|float|bool`)
  - missing `required` variables for a selected `(profileId, sutId)` at create time
- Warnings (allow save):
  - incomplete matrix coverage (some `(profileId, sutId)` pairs missing values for non-required variables, or missing optional pairs)
  - definitions declared but never referenced (optional UX signal)

---

## 7) Implementation plan (editor-first)

This is a concrete, implementation-oriented checklist. Treat the bundle as the SSOT; the editor is responsible for keeping `variables.yaml` consistent.

### Bundle + API surface

- [x] Store scenario variables in `scenarios/bundles/<scenarioId>/variables.yaml` (optional file).
- [x] Store bundle-local SUT definitions under `scenarios/bundles/<scenarioId>/sut/**` (SSOT for SUTs used by this scenario).
- [x] Scenario Manager: add endpoints to read/update `variables.yaml` for a scenario bundle (avoid “generic file read”; keep it narrow/safe).
- [x] Scenario Manager: add endpoints to list/get bundle-local SUTs for a scenario (used by the editor and create-swarm UI).
- [x] Scenario Manager: add endpoints to read/write/delete bundle-local `sut/<sutId>/sut.yaml` (editor CRUD).
- [x] Scenario Manager: validate `variables.yaml` on bundle upload/replace and on `variables.yaml` write.

### Data model + validation (Scenario Manager)

- [x] Parse `variables.yaml` into a typed model (`definitions`, `profiles`, `values.global`, `values.sut`).
- [x] Enforce strict schema rules:
  - [x] unique `definitions[*].name`
  - [x] `scope in {global,sut}` and `type in {string,int,float,bool}`
  - [x] unique `profiles[*].id`
  - [x] reject unknown keys in `values.*` not present in `definitions`
  - [x] reject type mismatches
- [x] Coverage validation levels:
  - [x] **Editor save**: allow incomplete matrix coverage but emit warnings (return warnings to UI).
  - [x] **Create swarm**: validate the selected `(variablesProfileId, sutId)` is fully resolvable for all `required: true` vars (hard error).

### UI (Scenario Editor + Create swarm)

- [ ] Editor: CRUD `definitions` and `profiles` in one UI (single `variables.yaml` file).
- [ ] Editor: grid editor for `values.sut[profileId][sutId]` (profile × SUT).
- [x] Editor: show validation warnings returned by Scenario Manager (e.g. incomplete required coverage, unknown sut references).
- [x] Editor: raw `sut.yaml` CRUD per bundle-local SUT.
- [x] Create swarm dialog (legacy UI):
  - [x] show `variablesProfileId` selector only if `variables.yaml` exists
  - [x] require `variablesProfileId` if variables exist (profiles present)
  - [x] require `sutId` + `variablesProfileId` if any `sut` vars exist
- [ ] Create swarm dialog (UI v2): implement equivalent behavior in `ui-v2/` (tracked separately).

### Orchestrator integration (create-time compilation)

- [x] Extend `SwarmCreateRequest` to include `variablesProfileId` (and optionally an explicit toggle like `refreshSutFromTemplate` if you keep template refresh at create time).
- [x] Orchestrator: fetch `variables.yaml` and bundle-local `sutId` definition from Scenario Manager during `swarm-create`.
- [x] Orchestrator: resolve `vars` for the chosen `(variablesProfileId, sutId)`:
  - [x] `vars = values.global[profileId] + values.sut[profileId][sutId]` (flat map)
  - [x] hard error on missing `required` values (no fallback chains)
- [x] Orchestrator: inject resolved `vars` into each bee config under a single, reserved key: `config.vars` (map) so workers can use it at runtime without bespoke per-worker wiring.
- [x] Orchestrator: record `variablesProfileId` and a small `vars` summary in the Hive journal entry for `swarm-create` (avoid dumping secrets).

### Worker SDK (Pebble + SpEL context)

The worker templating context is built in two main places today:
- `MessageTemplateRenderer` (HTTP/Request builder style templates)
- `TemplatingInterceptor` (payload templating step)

Implementation intent:

- [x] Standardize a single config location for variables: `config.vars` (map).
- [x] Add `vars` to Pebble context maps passed to the renderer:
  - [x] `MessageTemplateRenderer`: include `ctx.put("vars", <resolvedVarsFromConfig>)`
  - [x] `TemplatingInterceptor`: include `templateContext.put("vars", <resolvedVarsFromConfig>)`
- [x] Add `vars` to SpEL root variables inside `eval(...)`:
  - [x] `PebbleEvalExtension`: if `context.getVariable("vars")` exists, include it in the root map as `vars`

### Tracing / observability (correlation + idempotency)

Follow `docs/correlation-vs-idempotency.md` for the semantics.

- [ ] Orchestrator: include `swarmId`, `correlationId`, and `idempotencyKey` in logs around:
  - [ ] `swarm-create` request handling
  - [ ] variables resolution (profile + sut selection)
  - [ ] any “refresh SUT from template” step
- [ ] Orchestrator → Scenario Manager HTTP calls:
  - [x] propagate `correlationId` + `idempotencyKey` as request headers (choose explicit header names and keep them consistent across services)
  - [x] log both fields on the Scenario Manager side for these endpoints
- [ ] Worker-side: when template rendering fails (Pebble/SpEL), ensure the exception logs include enough context to trace:
  - [ ] worker role/instance + swarmId (where available)
  - [ ] correlationId/idempotencyKey from the triggering control signal (where available)

### Tests (targeted)

- [x] Scenario Manager: unit tests for `variables.yaml` parsing + validation (types, unknown keys, required coverage).
- [x] Worker SDK: tests that `vars.*` is accessible in Pebble and in `eval(...)`.
- [x] Orchestrator: a focused test that a `swarm-create` with `(sutId, variablesProfileId)` injects `config.vars` into the produced plan/config.
