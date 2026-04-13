# Scenario Manager Service

Manages simulation scenarios over a REST API.

See the [architecture reference](../docs/ARCHITECTURE.md) for endpoint and signal details.

## Scenario schema

Scenarios describe swarms in YAML (see [`scenarios/`](scenarios/)). Each bee can declare overrides by embedding a
`pockethive.worker.config` block inside its `config`, for example:

```yaml
config:
  pockethive:
    worker:
      config:
        ratePerSec: 12
        message:
          path: /api/guarded
          body: warmup
```

The Scenario Manager merges those maps into `SwarmPlan.bees[*].config`, and the Swarm Controller immediately emits the
corresponding `config-update` signals when the swarm starts. No environment variables are required for logical worker
settings—the scenario (or the service defaults under `pockethive.worker.*`) is the single source of truth.

---

## Bundle diagnostics

At startup and on every reload, Scenario Manager validates every bundle it finds on disk.
Failures are surfaced via the REST API and the UI — no server logs required.

### Why a bundle may be defunct

A bundle loads successfully but is marked **defunct** when it cannot be used to create a swarm.
The `defunctReason` field on the scenario summary explains why.

| Cause | Reason shown |
|---|---|
| Missing `id` field in `scenario.yaml` | `Scenario is missing a required 'id' field` |
| No `template:` block | `Scenario has no swarm template defined` |
| Controller has no `image:` | `Controller image is not defined` |
| A bee has no `image:` | `Bee 'X' has no image defined` |
| Image tag not in capability manifests | `No capability manifest found for image '...' (bee 'X'). Check that this image version is installed.` |

**How to fix:** correct the `scenario.yaml` or add the missing capability manifest YAML to
`scenario-manager-service/capabilities/`, then call `POST /scenarios/reload` or restart the service.

### Why a bundle may fail to load entirely

A bundle fails to load when it cannot be parsed at all. These failures appear in
`GET /scenarios/failures` and in the UI warning banner.

| Cause | Reason shown |
|---|---|
| Malformed YAML or JSON | `Could not read scenario file: <parse error location>` |
| Two bundles share the same `id` | `Duplicate scenario id 'X' — another bundle at '...' was loaded instead` |

A load failure in one bundle does **not** prevent other bundles from loading.

### Viewing failures

- **UI (Scenarios page):** a collapsible warning banner lists all load failures. Defunct bundles
  show a red `DEFUNCT` badge; selecting one shows the reason in the details panel.
- **UI (Create Swarm modal):** defunct templates appear greyed out with a tooltip showing the reason.
  They cannot be selected.
- **API:** `GET /scenarios/failures` returns load failures. `GET /scenarios?includeDefunct=true`
  returns all scenarios including defunct ones with `defunct` and `defunctReason` fields.

### Triggering a reload

After fixing a file on disk, call `POST /scenarios/reload` or restart the service. The UI
Refresh button re-fetches the lists but does **not** trigger a server-side reload.

---

## REST API reference

Full endpoint documentation: [`docs/scenarios/SCENARIO_MANAGER_BUNDLE_REST.md`](../docs/scenarios/SCENARIO_MANAGER_BUNDLE_REST.md)

Key endpoints added by the bundle diagnostics feature:

| Endpoint | Description |
|---|---|
| `GET /scenarios?includeDefunct=true` | All scenarios with `defunct` + `defunctReason` fields |
| `GET /scenarios/defunct` | Defunct scenarios only |
| `GET /scenarios/failures` | Bundles that failed to load entirely |
| `GET /api/templates` | All scenarios for the Create Swarm modal, including defunct |
