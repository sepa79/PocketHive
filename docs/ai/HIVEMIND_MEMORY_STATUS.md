# PocketHive memory maintenance status

Status: partial cleanup verified; learning lifecycle and historical-thread reconciliation remain blocked by missing MCP capabilities.
Rechecked: 2026-09-10, `ee424015` plus the existing working tree, project `pockethive`.

## Current capability and correction check — 2026-09-10

The user requested memory correction and retirement of this file. The selected global
server reports API **0.5.6**. A fresh `tools/list` request to the configured MCP endpoint
returned **45 tools, no continuation cursor**. Existing-learning resolution/supersession
and historical rule-check concern linking are still absent; this is not a stale client
catalogue. HiveMind's `docs/specs/api/automatic-recall.md`, section "First-slice boundaries",
also identifies those operations as subsequent work. No alternate endpoint or backing
storage was used.

The new automatic recall filters unclassified knowledge and historical blocked checks.
It does not change lifecycle: explicit learning search still returns all **23 active
learnings**, including the fourteen correction candidates below. Their prior feedback
is not retirement. Keep this register until supported lifecycle operations complete its
remaining gate; the user's requested deletion is not yet completed.

Twelve old journal records were corrected or retired using recorded decisions/reviews:
four atomic corrections, seven additional obsolete B02 entries, and the closed B01
design-evidence wording finding. The two completed correction reports were marked resolved.
Fresh open-entry search, branch brief and a newly opened context exclude the retired
blockers. Unresolved CP/Rabbit/SSOT concerns and SEL-R1 remain open. Automatic recall is
budgeted/truncated; missing items are not evidence of resolution. Explicit searches remain
necessary. No old blocked rule check was rewritten and no runtime tests were rerun.

HiveMind evidence:

- Session: `sess-6ec1f2bc56f75be8f981c87191d4c500`.
- Current direction: `ent-e1350b68caa63f6447b9ddb6d8e43697`.
- Superseded continuation handoff: `ent-68c8816c84841454c8cc565268137f89`.
- CSV-R1/Redis correction review reconciliation: `ent-61dcba44c4767da40e42005e162a79d3`.
- Candidate/empty-YAML decision reconciliation: `ent-a580a6a7afbc75c7a011df34a4b8885d`.
- Current capability limitation: `ent-d7994aab46c60733d59d06a9151ad801`.

The current user rejected the previous GPT-5.6 subagent execution workflow. Historical
Terra/Sol assignments do not authorize automatic continuation. Ports/adapters and SSOT
remain the objective; the execution plan needs correction before further migration.
This does not revoke historical scoped review evidence or accept full B02.

## Current interpretation

The architecture plan is `docs/inProgress/work-plane-module-boundaries.md`; its execution
sequence is under correction following the 2026-09-10 review above.
CP-N01 is fixed and committed in `19d56091`; CP-N02–CP-N09 and the broader Rabbit/SSOT
findings remain open. Those findings predate the sink-splitting phase. Accepted scoped
extractions do not imply that every production sink has gone. `SwarmLifecycleSteps`
remains explicitly excluded. Historical entries do not authorize a push or deployment.

This file is a temporary correction register for known stale memory, not a second
architecture contract. Follow the linked living docs/current plan. Once supported
lifecycle operations retire or replace these records and fresh reads confirm it,
archive the corresponding rows. Do not assume feedback has removed them from startup.

## Historical connected capability check — 2026-09-07

Both tool discovery and an actual `tools/list` request against the configured global
HiveMind MCP endpoint identified server version **0.5.4**. No other endpoint or local
memory server was used. Available maintenance tools include:

- `project_review`, `admin_memory_review`;
- `entry_mark`, `entry_correct`;
- `admin_project_delete`, `admin_project_merge` (whole-project operations, unused);
- `learning_search`, `learning_feedback`, `learning_capture`.

The catalogue does **not** expose a lifecycle update/resolve/supersede operation for
an existing learning, or resolution links for historical blocked rule checks. The
local HiveMind plan proposes those capabilities; proposal names are not callable tools.
The administrative cleanup endpoints are exposed, but deleting PocketHive is not an
appropriate substitute for retiring a stale record. Backing storage was not accessed.

The user confirmed on 2026-09-07 that the new lifecycle endpoints are not yet ready
and are being implemented separately; they will notify when available. Resume only
the pending lifecycle/retrieval gate when that capability is delivered. No HiveMind
implementation files or deployment were changed as part of this PocketHive task.

Fresh reads after mutation confirmed that corrected old entries disappeared from the
`open` entry search. However, `recent_decisions` still includes a superseded decision,
and `recent_risks` includes resolved/superseded historical risks. These sections are
history, not current work queues. The project brief's `latest_plan_ref` also initially
retained the former session plan path despite the new plan journal entry. The explicit
current plan above takes precedence; do not assume all brief projections apply lifecycle
filtering or derive the latest plan from `entry_append`.

A dedicated verification session with explicit `plan_ref` then refreshed
`latest_plan_ref` to `docs/inProgress/work-plane-module-boundaries.md`. This fixes the
plan pointer; it does not retire stale active learning records or historical checks.

## Completed journal changes

Active plan reference: `ent-785241845c8b173939148c66c31e7aa3`.
The following exact records were updated using supported lifecycle tools:

| Entry | Result |
|---|---|
| `ent-907f130c253fe4c74f1ca9aac494dc14` | superseded |
| `ent-a1d9f9697e6b4a95ce47e32b8c5b32c8` | superseded |
| `ent-f0086c80ab1fbc4b336c8f213e76d191` | superseded |
| `ent-26d6f3fc303e481ff0c1f119fb93a833` | superseded |
| `ent-a66d347c3c173b63f31432a85541dd96` | superseded |
| `ent-ac8fb51a48a125b9b4370bbf0c2d6329` | superseded |
| `ent-632545b2f0af0901cf7d2d35266dfffe` | superseded |
| `ent-6b40057d087a571f121680301bdd88c7` | resolved |
| `ent-95f643593cd579810b45af498608bb42` | resolved |
| `ent-28faaeec6a57b5ee729121282c14bd60` | superseded |
| `ent-36720e15cc4428938aad8409c9a856e3` | superseded |
| `ent-fb6a844924ef68c0b699cea3a6354b51` | Superseded atomically by corrected open sink risk `ent-f43c3bbc59816a19c60d0ddd27d7ba05` |
| `ent-16a7bab671c05aba5065e1b438b7750e` | Prior sink-first sequencing superseded by current decision `ent-2f558fbc0b5ea897f1d4f8fa863df83b`; completed phase remains accepted |

Reasons and successor references are recorded in HiveMind. The removed E2E-audit risk
was resolved because its implementation was deleted, not because the report was old.
The old general sink list was replaced with the remaining concerns, not marked all fixed.
Current Rabbit/SSOT risks, CP-N09, and the explicit test-scope exclusion were retained.

## Learning corrections pending lifecycle support

Reviewed all **23** learnings returned by project learning search and **107** open journal
entries returned by the scoped lifecycle search. This is the retrieved inventory, not a
claim of complete pagination or revalidation of every historic environment incident.
Fourteen learning records received correction comments; their server status remains active.
Do not execute their old recommended actions without applying these corrections.

| Learning | Current interpretation / required maintenance |
|---|---|
| `lrn-49902c6cfd500bdbddd26ae257af69c2` | Historical finding is correct for 5e68eec4; CP-N01 is now committed/fixed in 19d56091, not an open next action. CP-N02..09 remain open. Active order is docs/inProgress/work-plane-module-boundaries.md, not a separate CP queue. No claim of deployment for 19d56091. |
| `lrn-8921840239f8f5560a1831c2c6a8095b` | Keep producer-derived fixtures and canonical codec/schema validation. Retire the instruction to maintain the removed separate E2E parser/capture audit; docs/archive/control-plane-simplification-plan.md records its deletion. SwarmLifecycleSteps remains excluded. |
| `lrn-8b284f065c0171d42019d6cf349774bd` | Historical documentation audit evidence only. Do not reuse its export/import workspace or revision as the current baseline; use current repository/contracts and currently advertised HiveMap capabilities. No claim about scenario ZIP contract changes. |
| `lrn-f5e533da438730a0070c626a28ad72ac` | Partially stale: stopped-only single-source Redis listName editing exists; see docs/archive/redis-stopped-source-list-selector-plan.md and current runtime guard. Destination, multi-source and connection settings do not thereby become live-mutable. Do not seed/copy data as an implicit workaround. |
| `lrn-a16cc7698fdda30134e8abf15d7bbff3` | metrics_query and former Node MCP operational guidance are retired; use docs/mcp/README.md and NODE_TOOL_MIGRATION_LEDGER.md. The lifecycle cleanup concern remains open where independently evidenced; do not invent a direct backend metrics replacement. |
| `lrn-07f784906e0676065741ac109f5b565e` | Preserve delivered logRef/retention decisions; implementation plan has moved to docs/archive/observability-decommission-plan.md. Current owner is docs/observability.md; this is not future implementation work. |
| `lrn-d4b8e5d0d60dea8ac04b281b485ed060` | Planning/implementation phase completed; docs/archive/observability-decommission-plan.md records completion. Do not restart Loki/log-aggregator removal; consult current docs/observability.md. |
| `lrn-78db7555991edd716d4126c32d23acec` | Candidate direction is historical: observability migration was delivered. Current contracts in docs/observability.md replace the instruction to write/start another decommission plan. |
| `lrn-c323ce2c365452cb7fe3c32fd5f0de24` | Historical NFS reload incident was addressed by digest polling and exact applied-digest acknowledgement (9148c94b; lrn-eafc6da1092caee88b82ef815178974d). Preserve the mechanism lesson; do not present as an unverified current deployment failure. |
| `lrn-ca0d2232b5ffbace538cb6301de3001e` | Historical aggregate-config omission is fixed in current SwarmWorkersAggregator configFrom/update/snapshot code. Do not reopen this exact omission; independent freshness and SSOT findings remain open. |
| `lrn-8d6eade97d72a2865f92becd6f6620df` | Historical first Swarm run/outcome watcher incident; later accepted runs and removal of the separate E2E audit supersede this as a universal current blocker. CP-N09 remains a separate open chronology issue. |
| `lrn-f988f478d487ab347d0b7dc87ed06252` | Retain disk-exhaustion diagnosis only. Historical DROP/TRUNCATE instructions are not current authorization or an automatic repair; require current scoped evidence and supported governed operations. This task does not access or clean runtime data. |
| `lrn-e95bfd4cbdddda34efa72504aa25bae4` | Tool discovery guidance is duplicated with lrn-167a22ae2d6c0887b3b6fc5ef4b0fa0a. Current workflow is docs/ai/HIVEMIND_WORKFLOW.md. Actual 0.5.4 tools/list lacks learning lifecycle mutation; hidden tool discovery does not create an absent endpoint. |
| `lrn-167a22ae2d6c0887b3b6fc5ef4b0fa0a` | Duplicate tool-discovery guidance; use docs/ai/HIVEMIND_WORKFLOW.md as current workflow. Actual 0.5.4 tools/list checked; learning lifecycle mutation is absent, not merely undiscovered. |

Remaining nine learnings were retained as scoped guidance/evidence. Numeric deployment
observations are historical; configuration/version guidance requires the current environment.

- `lrn-9f163bbd254551bc2a82d3017f69e101`: PocketHive RabbitMQ integration is broader than the shared worker input/output adapters
- `lrn-eb109fcade7b3076787bd204fcf3f973`: PocketHive now has a first HiveMap code-quality overlay, but repository indexing only sees it once the file exists in the indexed Git revision; with that committed overlay, HiveMap coverage jumps from 3 to 996 PocketHive code files while duplicate-responsibility stays at zero under the current exported/public heuristic.
- `lrn-eafc6da1092caee88b82ef815178974d`: NFS-visible configuration apply requires content polling plus an exact applied-digest acknowledgement
- `lrn-0e9a3f4f352a621bc8f8cf4f2cadd29f`: Compact deltas require a fresh full projection for operation postconditions
- `lrn-8a78a6352fedb840ea8bdf50b0b82fc4`: HTTP 202 remove acceptance must wait for operation and registry postconditions
- `lrn-e83d717898c55179d8f972e62688a23a`: SUT endpoint identity is canonical only in the endpoints map key
- `lrn-eb4f268ca217fe7a9428309155bc8be3`: pockethive-development test deploy must update POCKETHIVE_VERSION runtime env before HiveForge update.
- `lrn-76478303a7cfcdb2c9d79add8015f428`: Docker Swarm expands {{ ... }} in service env values; app templates must be escaped at the Swarm adapter boundary.
- `lrn-aa22202b6b5c0ad851f4ae2f4e11a4f2`: HiveMind MCP must be registered with Codex MCP, not only VS Code MCP, for Codex sessions to see it.

## Remaining gate

1. Expose and deploy learning lifecycle mutation through the selected global MCP server,
   with explicit IDs, reason, successor/evidence, and conflict/idempotency semantics.
2. Apply the partial replacements/retirements above without adding competing active copies.
3. Link historical blocked checks to their actual resolved/deferred concerns; do not
   rewrite checks or blanket-close them because newer tests passed.
4. Verify fresh project/branch startup, learning search, and open-thread results. Stale
   CP-N01, removed E2E audit, old observability plan, and retired Node tools must not appear
   as current next actions. Preserve CP-N02–CP-N09 and all independently open defects.

Until that gate passes, project memory cleanup is **not complete**. The local workflow
requires this correction register so stale returned advice is not silently acted upon.
