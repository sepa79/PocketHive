# Config Key Normalisation — Plan

> Status: **in progress**.  
> New code mostly follows a single key style; this plan tracks remaining cleanup and enforcement.

Goal: converge on a single key style (camelCase) across worker configs, IO configs, scenarios, and capabilities, and remove relaxed/heuristic parsing in code. This should make configuration predictable, discoverable, and NFF‑compliant.

---

## 0. High-level tasks

- [ ] Baseline & conventions
  - [ ] Decide the canonical style: `camelCase` for all JSON/YAML keys exposed to users.
  - [ ] Catalogue current variants in use:
    - `ratePerSec` vs `rate-per-sec`
    - `singleRequest` vs `single-request`
    - `baseUrl` vs `base-url`
    - any others in worker configs, IO configs, and scenarios.
  - [ ] Document the convention briefly in `docs/sdk` and reference it from worker docs.

- [ ] Worker `application.yml` normalisation
  - [ ] For each worker service (generator, processor, moderator, postprocessor, trigger):
    - [ ] Update `pockethive.worker.config.*` keys to camelCase.
    - [ ] Ensure IO config blocks (`pockethive.inputs.*`, `pockethive.outputs.*`) also use camelCase for nested fields.
    - [ ] Verify that defaults still bind correctly via Spring Boot configuration properties.
  - [ ] For shared SDK configs (`SchedulerInputProperties`, `RedisDataSetInputProperties`, etc.), ensure examples and comments use camelCase.

- [ ] Scenario & capability normalisation
  - [ ] Scan `scenarios` bundles for snake/dashed keys and convert to camelCase where they map to worker config.
  - [ ] Update `scenario-manager-service/capabilities/*.latest.yaml` config field names to camelCase so the UI presents a consistent shape.
  - [ ] Adjust any e2e tests that assert on raw config payloads or status snapshots to expect camelCase keys.

- [ ] Parsing/merging simplification
  - [ ] Identify helper code that currently tolerates multiple key styles (e.g. relaxed map lookups, aliasing).
  - [ ] Remove bespoke relaxed-key logic, relying on:
    - Spring Boot’s configuration binding into POJOs for `application.yml`.
    - Consistent camelCase field names / `@JsonProperty` for JSON payloads.
  - [ ] Ensure control-plane config updates are treated the same way: only camelCase keys are supported going forward.

- [ ] Backwards compatibility & migration notes
  - [ ] Decide on the support stance for legacy config keys:
    - For internal configs (repo-owned YAML), migrate fully to camelCase.
    - For external users, document the change and the timeframe in which non-camelCase keys will stop working (if we choose to enforce that).
  - [ ] Add a short “config key migration” note to `CHANGELOG.md` and relevant worker docs.
  - [ ] Optionally add a startup log warning if legacy key patterns are detected in `rawConfig` (e.g. `base-url`), to ease migration without silent behaviour changes.

---

## 1. Constraints & principles

- NFF: no silent fallbacks between different key shapes. Once normalised, each logical field has exactly one canonical key.
- KISS: prefer Spring/JSON binding over manual map lookups; where map access is needed, use the canonical camelCase key only.
- Control-plane and IO topology remain plan-driven; this plan affects only configuration keys, not routing or resource naming.
