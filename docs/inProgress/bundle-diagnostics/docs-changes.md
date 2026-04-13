# Documentation Changes

The following documentation files must be updated as part of this feature. Changes should be made alongside the code, not after.

---

## 1. `docs/scenarios/SCENARIO_MANAGER_BUNDLE_REST.md`

### Add new section: `GET /scenarios/failures`

Add after the existing `GET /scenarios/defunct` section:

```markdown
### List bundle load failures

`GET /scenarios/failures` → `application/json`

Returns bundles that could not be loaded at all during the last reload. These are
distinct from defunct scenarios — they have no scenario id and cannot appear in
the scenarios list.

Response (200):
\`\`\`json
[
  {
    "bundlePath": "bundles/my-broken-scenario",
    "reason": "Could not read scenario file: mapping values are not allowed here at line 5"
  }
]
\`\`\`

Returns `[]` when all bundles loaded successfully.
```

### Update `GET /scenarios` section

Add note about new `defunct` and `defunctReason` fields:

```markdown
Each item now includes:
- `defunct` (boolean) — `true` if the bundle loaded but cannot be used to create a swarm
- `defunctReason` (string or null) — plain-English reason; null when not defunct
```

---

## 2. `scenario-manager-service/README.md`

### Add new section: `Bundle diagnostics`

Add after the existing `Scenario schema` section:

```markdown
## Bundle diagnostics

When the Scenario Manager loads bundles at startup (or on reload), it validates
each one. Bundles that fail validation are marked as **defunct** and excluded from
the template picker. Bundles that cannot be parsed at all are recorded as
**load failures**.

### Why a bundle may be defunct

| Reason | How to fix |
|---|---|
| Missing `id` field | Add `id:` to `scenario.yaml` |
| No `template:` block | Add a `template:` section with `image:` and `bees:` |
| Controller or bee has no `image:` | Add the missing `image:` field |
| Image tag has no matching capability manifest | Add a capability manifest YAML to `capabilities/` or update the scenario to use an installed tag |

### Why a bundle may fail to load entirely

| Reason | How to fix |
|---|---|
| Malformed YAML or JSON | Fix the syntax error at the reported line/column |
| Two bundles share the same `id` | Rename the `id` in one of the conflicting `scenario.yaml` files |

### Viewing failures

- **UI**: The Scenarios page shows a warning banner listing all load failures and
  marks defunct bundles with a red badge. The details panel shows the exact reason.
- **API**: `GET /scenarios/failures` returns load failures. `GET /scenarios?includeDefunct=true`
  returns all scenarios including defunct ones with `defunct` and `defunctReason` fields.
- **Reload**: After fixing a file on disk, call `POST /scenarios/reload` or restart
  the service to pick up the changes.
```

---

## 3. No other documentation changes required

The following files do not need changes:
- `docs/scenarios/SCENARIO_CONTRACT.md` — contract is unchanged
- `docs/scenarios/SCENARIO_VARIABLES.md` — unchanged
- `docs/scenarios/SCENARIO_PATTERNS.md` — unchanged
- `common/worker-sdk/README.md` — unchanged
- Any orchestrator or swarm controller docs — unchanged
