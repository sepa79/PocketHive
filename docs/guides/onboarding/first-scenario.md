---
title: Create your first scenario
pagination_label: Create a scenario
---

# Create your first scenario

| Reader context | Details |
| --- | --- |
| Audience | Scenario authors and evaluators using PocketHive MCP |
| Prerequisites | A running PocketHive environment, the repository-local MCP server, and a guarded `BUNDLES_ROOT` |
| Expected outcome | Author, validate, deploy, run, and clean up one tutorial scenario bundle |
| Last verified PocketHive version | PocketHive `v0.15.35` |

Configure PocketHive MCP and `BUNDLES_ROOT` using the
[MCP setup guide](../integrations/pockethive-mcp-and-bundles.md). The wizard
writes authoring files under that guarded root; only `scenario_deploy` stores
the bundle in Scenario Manager.

## 1. Plan and generate

Start a safe local REST example:

```text
wizard_start {"intent":"Create a local REST smoke scenario that sends POST /api/test to WireMock at one request per second.","bundleId":"my-first-scenario","protocol":"REST","target":"wiremock-local","endpoint":{"method":"POST","path":"/api/test"},"requestBody":"{\"event\":\"my-first-scenario\"}","ratePerSec":1}
```

Require `ready: true` and `humanCheckpoint: null`. If the wizard asks
questions, submit only the user's answers with `wizard_answer`, keeping the
same session. Review and generate the bundle:

```text
wizard_summary {"sessionId":"<sessionId returned by wizard_start>"}
wizard_complete {"sessionId":"<sessionId returned by wizard_start>"}
```

`wizard_complete` writes the scenario, request template, local WireMock
mapping, SUT definition, topology, and supporting documentation:

```text
my-first-scenario/
  scenario.yaml
  templates/
  mock-config/
    wiremock/
  sut/
  ...
```

Inspect the generated files and keep the folder name aligned with the scenario
`id` before continuing.

`wizard_start` and `wizard_summary` change only the in-memory session;
`wizard_complete` is the first file write. Verify that the summary still names
the intended endpoint, rate, bundle ID, and local target before allowing it.

## 2. Review one bounded input

Confirm the requested rate in `scenario.yaml`:

```yaml
config:
  inputs:
    scheduler:
      ratePerSec: 1.0
```

Also inspect the generated `POST /api/test` request and response. The local
source stack already provides a safe mapping for this endpoint. The generated
mapping remains in the portable bundle, but direct `scenario_deploy` does not
load it into WireMock; use the resumable MCP workflow when bundle-specific mock
loading is required. Change one bounded input at a time.

## 3. Validate without storing

```text
bundle_validate {"bundle":"my-first-scenario","validator":"scenario-manager-dry-run"}
bundle_validate_result {"jobId":"<jobId returned by bundle_validate>"}
```

Poll the same job until it succeeds. Dry-run validation checks Scenario Manager
admission without storing or replacing the bundle. If it fails, correct the
reported file and contract issue, then start a new validation job; use the
[scenario contract](../../scenarios/SCENARIO_CONTRACT.md) rather than guessing.

A successful result proves that this bundle passed the selected admission
rules at that time. It does not deploy the bundle or create runtime resources.

## 4. Deploy deliberately

```text
scenario_deploy {"bundle":"my-first-scenario"}
```

This remote write can replace an existing bundle with the same ID. In
**Scenarios**, select `my-first-scenario`, inspect its files and validation
result, and use **Save file** followed by **Reload & validate this** after any
UI edit.

![Scenario bundle selected and validated in the workspace](/img/guides/ui/scenarios-workspace.png)

**What to verify in this screen:** This reference image shows
`local-rest-topology`; in your run, confirm that `my-first-scenario` and its
intended file are selected, the tree is complete, and validation reports zero
errors. This confirms storage and validation, not swarm creation.

If deployment fails, leave the guarded authoring copy unchanged and resolve the
named MCP or Scenario Manager admission error. Do not change the bundle ID just
to hide an unresolved replacement conflict.

## 5. Run one swarm

1. In **Hive**, select **New swarm**.
2. Enter `my-first-scenario-run`, select `my-first-scenario` and
   `wiremock-local`, and keep network mode `DIRECT`.
3. Create the swarm, select **Details**, and verify `READY` with a fresh healthy
   Snapshot and one live runtime worker per planned role.
4. Start and verify `RUNNING` with those workers `enabled` and `live`; inspect
   topology and the swarm-filtered Journal.
5. Stop and verify `STOPPED` with workers `disabled` and still `live`.
6. Remove the swarm, require the correlated `Removed` Journal outcome, then
   confirm that it disappears from Hive.

The [swarm lifecycle guide](../operators/swarm-lifecycle.md) owns these
completion rules and recovery paths. Do not repeat a pending mutation or reuse
an ID while cleanup is uncertain.

This local run verifies the tutorial's documented lifecycle against the
selected mock. It is not qualification evidence for another deployment or an
external SUT.

## 6. Remove the deployed copy

After the swarm is gone, select `my-first-scenario` in **Scenarios**, choose
**Delete bundle**, and confirm that exact tutorial ID. This removes the
Scenario Manager copy, not the authoring folder under `BUNDLES_ROOT`. Keep or
delete that folder as a separate, deliberate file-management decision.

Verify that the bundle disappears from **Scenarios** while the guarded source
folder remains available for deliberate reuse.

## Source-checkout validation alternative

Contributors can validate the generated file without a swarm:

```bash
BUNDLE_SCENARIO="/absolute/path/to/scenario-bundles-root/my-first-scenario/scenario.yaml"
tools/scenario-templating-check/run.sh \
  --scenario "$BUNDLE_SCENARIO"
```

For HTTP request templates:

```bash
tools/scenario-templating-check/run.sh \
  --check-http-templates \
  --scenario "$BUNDLE_SCENARIO"
```

Use `./build-hive.sh --sync-scenarios` to resynchronize repository scenarios
into a running local source stack. This is not the external deployment path.

## Troubleshooting

| Symptom | Recovery |
| --- | --- |
| Wizard requests more information | Answer only its `humanCheckpoint`, preserve the session, and review `wizard_summary` again. |
| Validation fails | Correct the named file and contract issue, then submit a new dry-run job. |
| Bundle is absent from Scenarios | Inspect `scenario_deploy` and Scenario Manager admission; local files do not prove deployment. |
| A swarm action does not converge | Keep the ID and timestamps and follow the [lifecycle troubleshooting route](../operators/observability-troubleshooting.md#troubleshooting). |
| Cleanup is uncertain | Preserve the authoring folder and avoid reusing either tutorial ID. |

## Next step

- Adapt one input using [scenario patterns](../../scenarios/SCENARIO_PATTERNS.md).
- Learn [worker basics](../workers-basics.md) before changing roles.
- Continue with the [MCP guide](../integrations/pockethive-mcp-and-bundles.md)
  for resumable workflows and governed cleanup.
