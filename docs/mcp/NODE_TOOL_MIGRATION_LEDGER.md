# PocketHive Node MCP Tool Migration Ledger

## Status

`APPROVED PHASE 0 CONTRACT`

This ledger accounts for every one of the 103 tool registrations exposed by
the Node MCP at the migration baseline. It is a cutover record, not a second
tool catalogue. The Java service's `ToolDescriptor` manifest is the single
source of truth for tools that are actually published.

Rules:

- `MIGRATED` retains the current underscore tool ID and characterised contract.
- `REPLACED_BY` is an intentional contract change; no compatibility alias is
  published.
- `REMOVED_WITH_REASON` means the behaviour violates the approved target
  boundary or has no independent product value.
- `BLOCKED_BY_MISSING_OWNER_API` is unavailable until its owning PocketHive
  service publishes an approved API. The MCP does not emulate it.
- Dotted names below identify the Node registration. The old server exposed
  their underscore forms by default.

## Bundle, context, environment, and health registrations

| Node registration | Disposition | Reason or target |
|---|---|---|
| `bundle.list` | `REMOVED_WITH_REASON` | Server-side Git/bundle-root discovery is forbidden |
| `bundle.read` | `REMOVED_WITH_REASON` | The active client workspace owns source reads |
| `bundle.scaffold` | `REPLACED_BY scenario_workflow_generate` | Generation returns a deterministic proposed file set and never writes server source |
| `context.get` | `REPLACED_BY pockethive://environment` | Environment and knowledge are typed resources |
| `context.list-bundles-roots` | `REMOVED_WITH_REASON` | Bundle-root configuration is removed |
| `context.set-bundles-root` | `REMOVED_WITH_REASON` | One MCP instance has one immutable environment binding |
| `env.add` | `REMOVED_WITH_REASON` | Connection profiles are client-local |
| `env.current` | `REMOVED_WITH_REASON` | Connection profiles are client-local |
| `env.list` | `REMOVED_WITH_REASON` | Connection profiles are client-local |
| `env.remove` | `REMOVED_WITH_REASON` | Connection profiles are client-local |
| `env.status` | `REPLACED_BY pockethive://environment` | Live environment projection is resource-backed |
| `env.switch` | `REMOVED_WITH_REASON` | Server environment switching is forbidden |
| `health.check` | `REPLACED_BY pockethive://environment` | MCP initialize plus the environment resource is the supported connection proof |

## Workflow and wizard registrations

| Node registration | Disposition | Reason or target |
|---|---|---|
| `workflow.examples.list` | `REPLACED_BY pockethive://knowledge/scenario-examples` | Read-only knowledge resource |
| `workflow.examples.get` | `REPLACED_BY pockethive://knowledge/scenario-examples/{id}` | Read-only knowledge resource |
| `workflow.examples.recommend` | `REMOVED_WITH_REASON` | Keyword recommendation could choose intent without user confirmation |
| `workflow.config.get` | `REPLACED_BY pockethive://server/contract` | Generated contract resource |
| `workflow.profiles.list` | `REPLACED_BY pockethive://skills/index` | Versioned connected skills replace server personas |
| `workflow.profiles.get` | `REPLACED_BY pockethive://skills/{skillId}/{version}/SKILL.md` | Versioned connected skill |
| `workflow.role.check` | `REPLACED_BY scenario_workflow_answer` | Only typed, principal-bound QA dispositions enter workflow state |
| `workflow.hivemind.enrich` | `REMOVED_WITH_REASON` | HiveMind is an optional agent-host concern |
| `workflow.config.validate` | `REPLACED_BY pockethive://server/contract` | Invalid immutable deployment configuration fails startup |
| `workflow.list` | `REPLACED_BY scenario_workflow_list` | Principal-scoped canonical workflow list |
| `workflow.start` | `REPLACED_BY scenario_workflow_create` | Creates a workflow inside an explicit agent session |
| `workflow.source.read` | `REMOVED_WITH_REASON` | Server filesystem source reads are forbidden |
| `workflow.update` | `REPLACED_BY scenario_workflow_answer` | No free-form answer/plan merge |
| `workflow.status` | `REPLACED_BY scenario_workflow_get` | Canonical workflow projection |
| `workflow.result` | `REPLACED_BY scenario_workflow_get` | Canonical workflow projection |
| `workflow.evidence.render` | `REPLACED_BY pockethive://workflows/{workflowId}/evidence` | Bounded resource projection |
| `workflow.preview` | `REPLACED_BY scenario_workflow_generate` | Deterministic proposed file set |
| `workflow.generate` | `REPLACED_BY scenario_workflow_generate` | No server-side bundle write |
| `workflow.validate` | `REPLACED_BY scenario_bundle_validation_prepare` | Ticketed, verified binary upload |
| `workflow.deploy.start` | `REPLACED_BY scenario_bundle_publication_prepare` | Explicit governed publication intent |
| `workflow.deploy.status` | `REPLACED_BY scenario_bundle_publication_attempt_get` | Crash-safe attempt status |
| `workflow.deploy.resume` | `REPLACED_BY scenario_bundle_publication_reconcile` | Ambiguous writes reconcile and never replay |
| `workflow.deploy` | `REPLACED_BY scenario_bundle_publication_prepare` | No blocking orchestration wrapper |
| `workflow.verify` | `REPLACED_BY swarm_get` | Runtime owner remains authoritative |
| `workflow.verify.start` | `REPLACED_BY swarm_get` | No mirrored verification operation |
| `workflow.verify.status` | `REPLACED_BY swarm_get` | No mirrored verification operation |
| `workflow.verify.resume` | `REPLACED_BY swarm_get` | No mirrored verification operation |
| `workflow.patch` | `REPLACED_BY scenario_workflow_generate` | Changed intent invalidates artifacts and creates a new proposal |
| `workflow.report` | `REPLACED_BY pockethive://workflows/{workflowId}/evidence` | Bounded resource projection |
| `wizard.start` | `REPLACED_BY scenario_workflow_create` | QA skill uses the canonical workflow |
| `wizard.answer` | `REPLACED_BY scenario_workflow_answer` | QA skill uses typed elicitation |
| `wizard.summary` | `REPLACED_BY scenario_workflow_get` | QA skill uses the canonical projection |
| `wizard.complete` | `REPLACED_BY scenario_workflow_generate` | No second state machine |
| `wizard.enrich` | `REMOVED_WITH_REASON` | Inference/default enrichment is forbidden |

## Scenario Manager registrations

| Node registration | Disposition | Reason or target |
|---|---|---|
| `bundle.validate` | `REPLACED_BY scenario_bundle_validation_prepare` | Exact binary upload replaces server-directory packaging |
| `bundle.validate.result` | `REPLACED_BY scenario_bundle_publication_attempt_get` | Receipt/attempt projection replaces local job polling |
| `scenario.deploy` | `REPLACED_BY scenario_bundle_publication_prepare` | Explicit `CREATE` or `REPLACE`, never fallback |
| `scenario.list` | `MIGRATED` | Scenario Manager API |
| `scenario.get` | `MIGRATED` | Scenario Manager API |
| `scenario.raw.read` | `MIGRATED` | Scenario Manager API, deployed copy only |
| `scenario.raw.write` | `REMOVED_WITH_REASON` | Git-owned authoring forbids editing the deployed copy |
| `scenario.schema.read` | `MIGRATED` | Scenario Manager API |
| `scenario.template.read` | `MIGRATED` | Scenario Manager API |
| `scenario.contracts.get` | `MIGRATED` | Scenario Manager authoring-contract API |
| `scenario.capabilities.get` | `MIGRATED` | Scenario Manager capability API |
| `scenario.templates.catalog` | `MIGRATED` | Scenario Manager template catalogue API |

## Orchestrator registrations

| Node registration | Disposition | Reason or target |
|---|---|---|
| `swarm.list` | `MIGRATED` | Orchestrator API |
| `swarm.get` | `MIGRATED` | Orchestrator API |
| `swarm.create` | `MIGRATED` | Orchestrator API |
| `swarm.start` | `MIGRATED` | Orchestrator API |
| `swarm.wait-ready` | `MIGRATED` | Bounded reads of Orchestrator status |
| `swarm.stop` | `MIGRATED` | Orchestrator API |
| `swarm.remove` | `MIGRATED` | Orchestrator API |
| `debug.journal` | `MIGRATED` | Orchestrator journal API |
| `debug.hive-journal` | `MIGRATED` | Orchestrator journal API |
| `debug.tap` | `MIGRATED` | Orchestrator debug-tap API |
| `debug.tap.read` | `MIGRATED` | Orchestrator debug-tap API |
| `debug.tap.close` | `MIGRATED` | Orchestrator debug-tap API |
| `component.config-preview` | `MIGRATED` | Read/merge projection over documented Orchestrator APIs |
| `component.config-update` | `MIGRATED` | Orchestrator component-config API |
| `debug.config-update` | `REPLACED_BY component_config_update` | Compatibility alias removed |
| `runtime.cleanup.plan` | `MIGRATED` | Orchestrator runtime-cleanup API |
| `runtime.tail-worker-logs` | `MIGRATED` | Orchestrator runtime-debug API |
| `runtime.get-worker-version` | `MIGRATED` | Orchestrator runtime-debug API |
| `runtime.list-workers` | `MIGRATED` | Orchestrator runtime-debug API |
| `runtime.inspect-worker` | `MIGRATED` | Orchestrator runtime-debug API |
| `runtime.diff-swarm-runtime` | `MIGRATED` | Compose only documented Orchestrator read APIs |
| `runtime.control-plane-status` | `MIGRATED` | Compose only documented Orchestrator read APIs |
| `runtime.rabbit-topology-snapshot` | `MIGRATED` | Orchestrator-owned Rabbit topology API |
| `runtime.swarm-timeline` | `MIGRATED` | Orchestrator journal/status APIs |
| `runtime.manifest-validate` | `MIGRATED` | Compose only Orchestrator-owned runtime projections |
| `runtime.cleanup.execute` | `MIGRATED` | Orchestrator cleanup API plus HiveGate governance |

## Registrations blocked at an authority boundary

| Node registration | Disposition | Missing owner API |
|---|---|---|
| `debug.queues` | `BLOCKED_BY_MISSING_OWNER_API` | General queue summary outside the scoped Orchestrator topology contract |
| `metrics.query` | `BLOCKED_BY_MISSING_OWNER_API` | PocketHive metrics query/read-model API |
| `evidence.summary` | `BLOCKED_BY_MISSING_OWNER_API` | Authoritative aggregate evidence API |
| `mock.wiremock.list` | `BLOCKED_BY_MISSING_OWNER_API` | PocketHive mock-management API |
| `mock.wiremock.add` | `BLOCKED_BY_MISSING_OWNER_API` | PocketHive mock-management API |
| `mock.wiremock.reset` | `BLOCKED_BY_MISSING_OWNER_API` | PocketHive mock-management API |
| `mock.wiremock.requests` | `BLOCKED_BY_MISSING_OWNER_API` | PocketHive mock-observation API |
| `mock.wiremock.unmatched` | `BLOCKED_BY_MISSING_OWNER_API` | PocketHive mock-observation API |
| `mock.tcp.list` | `BLOCKED_BY_MISSING_OWNER_API` | PocketHive mock-management API |
| `mock.tcp.add` | `BLOCKED_BY_MISSING_OWNER_API` | PocketHive mock-management API |
| `mock.tcp.reset` | `BLOCKED_BY_MISSING_OWNER_API` | PocketHive mock-management API |
| `mock.tcp.requests` | `BLOCKED_BY_MISSING_OWNER_API` | PocketHive mock-observation API |
| `mock.tcp.unmatched` | `BLOCKED_BY_MISSING_OWNER_API` | PocketHive mock-observation API |
| `mock.tcp.scenarios` | `BLOCKED_BY_MISSING_OWNER_API` | PocketHive mock-state API |
| `mock.tcp.reset-scenarios` | `BLOCKED_BY_MISSING_OWNER_API` | PocketHive mock-state API |
| `mock.tcp.enable` | `BLOCKED_BY_MISSING_OWNER_API` | PocketHive mock-management API |
| `mock.tcp.disable` | `BLOCKED_BY_MISSING_OWNER_API` | PocketHive mock-management API |
| `mock.tcp.update` | `BLOCKED_BY_MISSING_OWNER_API` | PocketHive mock-management API |

## Accounting gate

The Phase 0 test extracts all Node registrations and compares them with this
ledger. It must prove exactly 103 unique rows, no missing registration, no
duplicate registration, one recognised disposition per row, and a valid target
for every `REPLACED_BY`. The cutover test then proves that no removed or blocked
registration is published by Java.
