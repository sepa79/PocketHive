# Finding provenance relative to the sink refactor — 2026-09-07

**Result: all eight SSOT findings and all seven RabbitMQ findings describe problems
already present before the sink-splitting phase. No newly introduced regression was
established for these fifteen finding groups.**

The presence of these findings is not evidence that the sink refactor failed its scope.
The preceding audits assess current-tree debt; they must not be interpreted as a negative
assessment of the behavior-preserving extraction work merely because that debt remains.

## Comparison boundary and method

- Before: `18d8cfbc`, the parent of `43dc1392`, the first sink-simplification commit.
- After: `5e68eec4`, the simplification closeout.
- Subsequent working-tree changes were examined separately, including the CP-N01 repair.
- This boundary covers the sink-splitting phase, not the entire earlier lifecycle rewrite.
  The baseline is a historical comparison point, not a proposed stable rollback target.

Compared Git file contents, relevant method bodies, and the old active call sites.
The selected evidence set contains 57 production/configuration/tooling files: 48 are
byte-identical across the committed comparison, seven are newly extracted file paths,
and two existing files changed. For the changed files, nine methods relevant to these
findings were compared and are byte-identical: six Redis/template validation methods
and readiness `metrics`, `isFullyReady`, and `recordHeartbeat`.

New paths were traced to their previous containing classes. An old line surviving a
`git blame` check alone was not treated as proof that its current interaction was old.
The old producers, consumers, validators, and topology usage were checked together.

## SSOT findings

| Finding | Classification | Evidence at the baseline |
|---|---|---|
| SSOT-01 — cleanup success | Preexisting | `RuntimeReconciliationService` and `RuntimeRemovalPostconditionVerifier` are unchanged. The old Orchestrator `SwarmSignalListener` already called `verifyAbsent` at lines 408 and 442, while orphan cleanup independently returned `REMOVED`. Extraction into `SwarmRemovalConvergenceHandler` did not introduce the competing success rule. |
| SSOT-02 — file journal paths | Preexisting | Reader, writer, and shared `RuntimeFilesystemLayout` are all unchanged. The reader already reconstructed `runId` paths instead of using the complete shared resolver. |
| SSOT-03 — Redis validators | Preexisting | SDK properties and adapters are unchanged. All five examined Redis semantic methods in `ScenarioBundleValidator` are unchanged. `ScenarioService` already constructed and invoked that validator; replacing manual construction with Spring injection did not create a new validation path. |
| SSOT-04 — request-template shape | Preexisting | `TemplateLoader` is unchanged; `validateRequestTemplateShape` is unchanged. The authoring validator was already active through `ScenarioService`. |
| SSOT-05 — REST DTO copies | Preexisting; producer records moved | Baseline `ScenarioController` already declared `RuntimeRequest`, `ScenarioRuntimeResponse`, and `VariablesResolveResponse` at lines 893, 896, and 902 and used them in its endpoints. The matching Orchestrator client records are unchanged. `50d456ee` moved producer records into files; it did not introduce a second set of wire shapes. |
| SSOT-06 — UI normalization | Preexisting | Network normalizer, workload guard, generated lifecycle parser, and Java `NetworkBinding` are unchanged. |
| SSOT-07 — worker freshness duplication | Preexisting; consumer moved | `SwarmWorkersAggregator` is unchanged. Baseline Controller `SwarmSignalListener` had its own 15-second constant, constructed the aggregator, separately updated lifecycle and aggregate observations, and published `workers.snapshot()`. Readiness heartbeat/metrics/TTL evaluation remains unchanged. The new handler did not create the second observation registry. |
| SSOT-08 — UI grant policy | Preexisting | Shared Java grant predicate, UI predicate, and handwritten UI constants are unchanged. |

`SwarmReadinessTracker` did receive a separate semantic change in `d634f750`: full-status
convergence now uses monotonic observation revisions with captured enablement instead of
wall-clock cutoffs. The [simplification log](../archive/control-plane-simplification-plan.md)
records the triggering convergence failure and its verification. This prevents claiming
the whole tracker is unchanged, but it did not introduce the heartbeat-age duplication
reported as SSOT-07. This provenance check does not independently certify every aspect of
that convergence change.

## RabbitMQ findings

| Finding | Classification | Evidence at the baseline |
|---|---|---|
| RAB-01 — control queue naming | Preexisting; cleanup/status consumers moved | Descriptors, Controller properties, Rabbit listener configuration, and startup verifier are unchanged. Baseline `SwarmRuntimeCore` lines 359–365 already deleted worker queues using `properties.controlQueueName`; baseline listener status used the same competing resolver. |
| RAB-02 — advertised control topology | Preexisting; publisher moved | `SwarmControllerRoutes`, worker properties/runtime, and descriptors are unchanged. Baseline Controller listener lines 1039–1042 already advertised routes from `SwarmControllerRoutes`, rather than the descriptor. |
| RAB-03 — CP binds Work settings | Preexisting | Worker CP auto-configuration, shared declarable factory, and the relevant properties/runtime configuration owners are unchanged. The unused Rabbit Work exchange requirement already existed. |
| RAB-04 — Work destination naming | Preexisting | `ContainerLifecycleManager`, environment factory, and `DebugTapService` are unchanged across the committed phase. Subsequent uncommitted metrics-property type extraction does not change destination naming. |
| RAB-05 — exchange declaration owners | Preexisting | Shared Java exchange declaration, broker definitions/configuration, and both debug scripts are unchanged. |
| RAB-06 — fixed UI/debug names | Preexisting | Subscription, decoder, active health-store consumer, and debug client are unchanged. |
| RAB-07 — ignored Rabbit settings | Preexisting | Rabbit properties, input factory/listener configuration, output adapter, and global poison-message customizer are unchanged. |

## Two earlier CP findings also checked

- **CP-N01:** Orchestrator `RabbitConfig`, shared descriptor/manager auto-configuration,
  service CP configuration, and properties already coexisted before the sink refactor and
  were unchanged by that phase. The later uncommitted repair is a separate change.
- **CP-N09:** Baseline Orchestrator `SwarmSignalListener` lines 181–184 already passed
  `result.timestamp()` to `operations.recordResult`. The operation coordinator and
  `SwarmOperation` chronology validation are unchanged. Moving result handling into
  `SwarmOperationTerminalHandler` did not introduce the clock-domain problem.

This does not constitute a new review of every CP-N02–CP-N08 structural finding.

## Verification limits and corrected interpretation

This follow-up performed source/history comparisons, not a new stack replay. The earlier
offline behavioral probes demonstrated the current defects; unchanged implementations
and preexisting call sites establish their provenance. No production code was modified.
The working tree was preserved; no checkout, commit, push, or deployment was performed.

The [SSOT audit](ssot-ownership-audit-2026-09-07.md) and
[RabbitMQ audit](rabbitmq-ownership-audit-2026-09-07.md) remain repair inventories.
**For these findings, classify them as inherited debt, not regressions caused by splitting
the sinks.** A claim that this phase made them worse would require additional before/after
evidence. Conversely, this attribution does not prove that every change in the phase is
regression-free; it answers the provenance question for the specified findings.
