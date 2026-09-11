# Separate topology / Scenario transfer review — 2026-09-10

Verdict: **changes requested; transfer not accepted**. Review requested explicitly after
implementation. Scope: `ee424015` plus the uncommitted topology/Scenario transfer and its
preceding Controller extraction. No implementation fixes, commits, pushes or deployed
checks were performed in this review.

## Findings

### TS-R1 — HIGH / P1: environment selection can disagree with bootstrap

`WorkerWorkConfigurationAdapter.compose`, lines 78–81, selects/materializes Rabbit settings
from config, then overlays bee.env. `RabbitWorkEnvironment.overrideProblems` checks Rabbit
fields, but not the IO selectors. A bee with config.outputs.type=RABBITMQ and
bee.env.POCKETHIVE_OUTPUTS_TYPE=NONE is accepted. The observed result is:

```text
worker environment: POCKETHIVE_OUTPUTS_TYPE=NONE
bootstrap: outputs.type=RABBITMQ (with complete Rabbit settings)
```

This violates the transfer's environment/bootstrap agreement condition and leaves two
selection authorities. The worker's WorkConfigurationCandidateValidator checks the parsed
selection against its startup definition, so the mismatch is discovered at the worker after
Controller plan acceptance/provisioning instead of at the planning boundary.

The overlay is inherited code retained by this transfer, not a newly introduced line.
The full Controller candidate gate is explicitly deferred; that does not establish the
claimed agreement of this concrete Rabbit output path. Keep this acceptance gap open.
Resolve selection once and use the result for both outputs, or reject conflicting selector
overrides before returning the plan. Cover both input and output selectors and Spring aliases.

### TS-R2 — HIGH / P1: settings facades bypass the configured naming port

`SwarmControllerProperties.Traffic`, lines 165–166, privately constructs a static
PrefixedWorkResourceNames; ControlPlaneContainerEnvironmentFactory constructs another.
Worker configuration/provisioning receive the injected port, while BufferGuardCoordinator,
SwarmQueueStatsCollector, SwarmWorkBindingsProjector and Orchestrator queue-list projections
continue through those independently selected instances.

The formula is centralized in one class, but configured capability consumption is not.
A behavioral probe supplying a name port that resolves input to `selected.in` produced:

```text
worker/provisioning path: selected.in
properties/guard/status projection: ph.default.in
```

This is not a request for a bean-identity test. It is an observable resource-identity mismatch
across consumers under the declared port contract. It is introduced by the partial port
transfer, and violates the human condition that the consumer actually uses the configured
port. Supply that same capability to all consumers or pass resolved immutable projections;
remove concrete resolver selection from properties/legacy environment facades. Do not solve
it by declaring the bypasses acceptable in architecture.

### TS-R3 — MEDIUM / P2: explicit null tuning is silently treated as omission

`RabbitWorkSettingsBootstrap.tuning`, line 54, converts null into an empty map. Its callers
use Map.get, so omission and an explicit `rabbit: null` are indistinguishable. The neutral
Scenario AUTHORING parser correctly rejects explicit null, while Controller accepts it and
produces default settings. Reproduced with inputs={type:RABBITMQ,rabbit:null}, outputs=NONE:

```text
AUTHORING: inputs.rabbit: Settings must be an object.
Controller: accepted {queue=ph.default.in,prefetch=50,concurrentConsumers=1,exclusive=false}
```

The null-as-omission helper predates this last transfer; connecting strict AUTHORING now
exposes the concrete acceptance divergence. It remains a gap in the transferred Rabbit
materialization contract, not a finding about unrelated YAML empty-object erasure.
Preserve field presence at this boundary and reject explicit null; only actual absence may
select optional tuning defaults. Add the input and output rejection cases before effects.

## Responsibility and source evidence

| Responsibility | Inspected path / header / effects | Verdict |
|---|---|---|
| RESP-WORK-RESOURCE-NAMES | WorkResourceNamesPort → PrefixedWorkResourceNames; WorkerWorkConfigurationComposition injects it into the configuration adapter and lifecycle provisioning. Static selections in Traffic and ControlPlaneContainerEnvironmentFactory remain; guard/stats/bindings call these projections. | Formula centralized; configured-port transfer incomplete (TS-R2). |
| RESP-WORK-RABBIT-SETTINGS | Rabbit input/output providers implement neutral ports; immutable AUTHORING tuning is distinct from RESOLVED settings. Bootstrap validates with canonical providers; RabbitWorkEnvironment exports the resulting map. | Tuning-only AUTHORING supported; null acceptance divergence TS-R3. |
| RESP-CONTROLLER-WORK-CONFIGURATION | Factory → injected port → adapter → naming/bootstrap/export → connection freeze → result. No provisioning inside the adapter. Env overlay remains after selection/materialization. | TS-R1 prevents environment/bootstrap agreement. |
| RESP-CONTROLLER-WORKER-PLAN / RESP-WORK-STATE | SwarmRuntimeCore.prepare accumulates local plans before accepted-context assignment/topology/provisioning. Worker candidate validator rejects definition-selection mismatch. | Validated rejections preserve state; TS-R1 is not rejected at this boundary. |
| RESP-SCENARIO-VALIDATE | Existing Scenario composition → injected WorkConfigurationParser → selected providers; WorkConfigurationFindings only prefixes canonical findings. Catalogue validation skips Work roots. Bundle/file/reference and non-Work checks retain their old paths. | Neutral delegation supported within inspected scope. No remaining local adapter parser construction found. |

Repository searches covered production Java references to queueName/hiveExchange,
swarmTrafficQueueName(s)/trafficQueueName(s), all PrefixedWorkResourceNames constructions,
WorkConfigurationFindings and concrete Rabbit/Redis/local parser imports in Scenario.
Inspected guard, stats, work-bindings, provisioning and Orchestrator queue-list callers;
these distinguish delegating source formulas from the actual configured capability.

The single existing import test now bans Scenario adapter-setting imports and retains
its topology-core infrastructure restrictions. It does not inspect intra-service wiring
or fully-qualified references; a pass does not exclude TS-R2. No extra scanner was added.

## Six review passes

| Pass | Result |
|---|---|
| Plan outcome | Blocked by TS-R1/TS-R2/TS-R3. Two code transfers exist, but point 3 is not fully satisfied. Deferred complete Controller validation is explicitly distinguished from proved behavior. |
| Style / boundaries | New production types have separate files and responsibility headers. No new mixed state/transport owner. Static concrete naming selections violate the intended consuming port boundary (TS-R2). Existing large service/settings files remain inherited debt. |
| Conciseness | Shared providers and diagnostic projection remove substantial duplicate validation. Static alternate compositions and null normalization should be removed, not covered by more exception paths. Bootstrap repeats AUTHORING/RESOLVED validation through the same owner; no duplicate field-rule implementation found there. |
| Security | New diagnostics do not echo settings/secrets and no authentication/public ingress changes were added. Retained env overlays can redirect adapter selection (TS-R1); no deployed security validation claimed. |
| Libraries | No new external library/module; existing Maven, Spring binding and shared parsers reused. |
| Readability / maintainability | Owner names and test evidence are clearer, but “read-only delegation” currently conceals a configured-port bypass. Contracts must reflect fixed call paths, not waive TS-R2. |

## Verification and limits

Independently reran the implementation's full targeted reactor command from
`topology-scenario-transfer.md`: **143 tests passed**, zero failures/errors/skips.
This includes the actual repository scenario bundle suite and import boundary checks.
`git diff --check` passed for tracked changes. Review did not repeat the full package/docs
build; those successes are implementation evidence, not newly executed review checks.

Three extra read-only behavioral probes invoked the real built configuration adapter and
neutral parser with the exact inputs recorded above. They reproduced all three findings.
Probe source/run helper are local at `/tmp/ph-transfer-review/ReviewProbe.java` and
`/tmp/ph-transfer-review/run-probe.py`; no production/test suite was edited to force outcomes.
These are compiled Java calls, not a source scanner or bean/module selection check.

No live broker, worker deployment, direct service port or Scenario persistence transaction
was tested. Existing lifecycle component tests support their covered rejection cases;
they do not cover selector mismatch or explicit null tuning. Recommendation: fix the
three findings and run a separate correction review before accepting this transfer.
