# Swarm Start Failure UI Notes (Archived)

## Status
- Archived as reference notes for the first round of swarm startup/runtime failure analysis.
- The observations here informed the implemented UI v2 Journal/Hive surfacing work and the follow-up lifecycle/health model plan.

## Reproducer
- Scenario: `local-rest-worker-crash`
- Failure mode: one worker process dies during startup, but the swarm-controller still reports the swarm as `READY` with `DEGRADED` health.

## Current UI v2 Behavior
- On Hive list, the swarm is shown as `READY` and `DEGRADED`.
- Details still show all workers from the template definition.
- Failed worker roles do not surface an explicit runtime error.
- The only visible hint is that the failed worker has no live runtime instance/status.
- This is effectively silent from a user perspective unless they infer the missing instance.

## Problem
- The UI does not clearly tell the user that a worker failed to start.
- The UI does not show a brief failure reason.
- The UI does not link to follow-up diagnostics.

## Better Target Flow
- Mark the failed component in red.
- Show a brief problem status next to the worker and/or swarm summary.
- Add direct links to diagnostics from the swarm details view.
- Preferred destination: Journal view, which can later expose links to logs.

## Recommended Delivery Order
- First bring at least the Journal basics from UI v1 into UI v2.
- Rationale:
  - UI v2 Hive already exposes failure symptoms, but not causes.
  - Orchestrator journal already contains useful failure details for template/start problems.
  - Without Journal access in UI v2, adding “open diagnostics” actions in Hive would point nowhere.
- Minimum useful Journal scope for UI v2:
  - Hive journal page
  - Swarm journal page
  - basic paging / latest entries
  - severity, type, timestamp, summary/message
  - filtering by swarmId and runId
  - direct navigation from Hive swarm details into the swarm journal
- After that, improve Hive diagnostics surfacing:
  - explicit failed/missing worker state
  - template/start/runtime phase markers
  - red failure cards / badges
  - shortcuts into relevant journal slices

## Current Constraint
- Journal-based troubleshooting links are not implemented yet, so the near-term improvement should focus on explicit visual surfacing of failed or missing worker instances.

## Additional Problems Observed
- UI v2 detail pages currently rely mostly on template metadata plus runtime snapshots.
- When a worker never comes up, details still show the template bee entry, but runtime status is effectively empty and silent.
- For template-phase failure, the journal already contains a useful alert payload with `context.phase=template` and a concrete error message, but UI v2 does not surface it.
- Browser console shows noisy 404s from `network-proxy-manager` binding lookups for swarms without active proxy binding; this is not the root problem, but it adds noise during diagnosis.
- Health modelling is inconsistent for runtime execution failures:
  - `ctap1` stays `RUNNING` / `OK`
  - generator keeps failing on every scheduled invocation due to template rendering errors
  - this means startup health and runtime work health are not clearly separated today
- Scenario Manager currently allows scenarios whose runtime behavior is likely broken, for example:
  - no bundle-local SUTs exposed
  - unresolved `{{ sut.* }}` placeholders still reaching worker config
  - invalid template expressions only failing once traffic starts

## CTAP Bundle Check
- Bundle tested: `ctap-iso8583-rbuilder-scenario.zip`
- Scenario id in bundle: `ctap-iso8583-request-builder-demo`
- Result:
  - ZIP upload to Scenario Manager succeeded.
  - Swarm `create` succeeded.
  - Swarm `start` succeeded.
  - This did not reproduce a `swarm-template` / controller bootstrap failure locally.
- Observed runtime issue instead:
  - Generator fails while rendering the message body template.
  - The failing expression is the `date_format(...)` usage in the JSON body template.
  - Error class in logs: `TemplatingRenderException` / `TemplateRenderingException`.
- Additional risk in the same scenario:
  - Scenario Manager reports no SUTs for this scenario.
  - Processor config still contains unresolved `{{ sut.endpoints['tcp-server'].baseUrl }}` after config update.
  - That unresolved SUT dependency is likely to fail later if traffic reaches the processor, but generator failure happens first.

## Template-Phase Failure Reproducer
- Scenario: `local-rest-invalid-volume-template`
- Failure mode:
  - Scenario Manager accepts the scenario.
  - Orchestrator starts the swarm-controller.
  - Swarm-controller fails during worker provisioning in template phase because generator carries an invalid Docker volume spec.
- Journal result:
  - `swarm-template` outcome is `Failed`
  - alert payload contains:
    - `context.phase = template`
    - message: `invalid volume specification: ':/app/invalid:rw'`
- Current UI v2 behavior for this case:
  - Hive shows `STOPPED` + `DEGRADED`
  - details still show 4 bees from the template
  - selected bee has no runtime snapshot
  - no explicit reason is shown even though the journal already has one
