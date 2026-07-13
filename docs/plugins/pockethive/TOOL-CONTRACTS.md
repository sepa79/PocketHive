# PocketHive MCP Tool Contracts

## Status
`LIVING CONTRACT`

This document defines the contract shape every PocketHive MCP tool must have.
It is intentionally mechanical so agents can implement, review, and test tools
without guessing.

## Tool Classes

| Class | Audience | Naming | May write files? | May call runtime APIs? |
|---|---|---|---|---|
| Public novice | End users through AI chat | `wizard_*` | Only on `wizard_complete` | Yes, where declared |
| Public operational | IDE users and agents | `bundle_*`, `scenario_*`, `swarm_*`, `component_*`, `debug_*`, `mock_*`, `dataset_*`, `contract_*`, `health_*`, `context_*`, `env_*` | Only where declared | Yes |
| Advanced authoring | Experienced agents | `session_*`, `pipeline_*`, `bee_*`, `template_*`, `variables_*`, `sut_*`, `traffic_policy_*`, `plan_*` | Yes, guarded bundle writes | Usually no, unless declared |
| Internal helper | MCP implementation only | private functions/modules | As needed internally | As needed internally |

## Tool Name Compatibility

Canonical MCP tool names use lower-case snake_case only. This keeps the same
server compatible with clients that reject dotted tool names, including GitHub
Copilot-style MCP integrations, and with OmniMCP's downstream tool registry.

`PH_MCP_TOOL_NAME_MODE=underscore` is the default and exposes only snake_case
names. `PH_MCP_TOOL_NAME_MODE=legacy` exposes the old dotted/hyphenated names
for older clients. `PH_MCP_TOOL_NAME_MODE=both` exposes both surfaces for a
short migration window, but should not be used for Copilot or OmniMCP
registration because invalid legacy names still appear in `tools/list`.

## Required Contract Fields

Every tool entry must document:

| Field | Meaning |
|---|---|
| Name | Exact MCP tool name |
| Class | Public novice, public operational, advanced authoring, or internal helper |
| Purpose | One sentence |
| Input schema | Required and optional input fields |
| Output schema | Required and optional output fields |
| Side effects | File writes, runtime calls, state changes, or none |
| Allowed sources | APIs/files the tool may read |
| Write scope | Paths or runtime resources the tool may mutate |
| Evidence value | What claim this tool can support |
| Failure modes | Explicit errors the tool may throw |
| Phase | Implementation phase |

## Contract Template

```yaml
name: tool_name
class: public operational
purpose: One sentence describing the user-visible job.
input:
  required:
    id: string
  optional:
    limit: number
output:
  required:
    ok: boolean
  optional:
    source: string
    sourceReason: string
sideEffects:
  files: none
  runtime: read-only
allowedSources:
  - PocketHive API: /example
writeScope: none
evidenceValue: What this result proves.
failureModes:
  - MISSING_CONFIG
  - NOT_FOUND
phase: 1
```

## Current Tool Ownership

| Tool family | Class | Primary doc | Side effects |
|---|---|---|---|
| `context_*` | Public operational | `MCP-SERVER.md` | In-memory context/settings signal |
| `env_*` | Public operational | `MCP-SERVER.md`, `CONFIG.md` | Settings signal, restart required where declared |
| `health_check` | Public operational | `MCP-SERVER.md` | Read-only |
| `contract_*` | Public operational | historical design: `docs/archive/pockethive-plugin/MCP-IMPROVEMENT-SPEC.md` | Read-only |
| `bundle_list`, `bundle_read` | Public operational | `MCP-SERVER.md` | Read-only |
| `bundle_validate`, `bundle_validate_result` | Public operational | historical design: `docs/archive/pockethive-plugin/MCP-IMPROVEMENT-SPEC.md` | Scenario Manager validation job state |
| `bundle_diff` | Public operational | historical design: `docs/archive/pockethive-plugin/MCP-IMPROVEMENT-SPEC.md` | Read-only |
| `bundle_docs_*` | Advanced authoring | historical design: `docs/archive/pockethive-plugin/MCP-IMPROVEMENT-SPEC.md` | Guarded bundle doc writes |
| `scenario_*` | Public operational | `MCP-SERVER.md` | Runtime scenario import/read |
| `scenario_contracts_get`, `scenario_capabilities_get`, `scenario_templates_catalog` | Public read-only contract discovery | Scenario Manager API | Read-only HTTP calls |
| `swarm_*` | Public operational | `MCP-SERVER.md` | Runtime swarm lifecycle |
| `component_config_preview` | Public operational | `MCP-SERVER.md`, `ORCHESTRATOR-REST.md` | Read-only runtime config merge preview |
| `component_config_update` | Public operational | `MCP-SERVER.md`, `ORCHESTRATOR-REST.md` | Runtime control-plane config update |
| `debug_*` | Public operational | `MCP-SERVER.md`, `EVIDENCE.md` | Runtime read/tap lifecycle only |
| `evidence_summary` | Public operational | `EVIDENCE.md`, `MCP-APPS.md` | Read-only aggregate evidence model |
| `workflow_*` | Public novice / agent-guided delivery | This document | Session state; generated bundle writes; optional runtime calls |
| `mock_*` | Public operational | historical design: `docs/archive/pockethive-plugin/MCP-IMPROVEMENT-SPEC.md` | Mock admin state and optional bundle mock-config writes |
| `dataset_*` | Public operational | historical design: `docs/archive/pockethive-plugin/MCP-IMPROVEMENT-SPEC.md` | Dataset seed/check/save |
| `wizard_*` | Public novice | `BUNDLE-WIZARD.md` | Session state; files only on complete |
| `session_*` | Advanced authoring | `BUNDLE-WIZARD.md` | Authoring session state and guarded bundle writes |
| `pipeline_*`, `bee_*`, `template_*`, `variables_*`, `sut_*`, `traffic_policy_*`, `plan_*` | Advanced authoring | `BUNDLE-WIZARD.md` | Guarded bundle writes |

## Global Tool Rules

- No tool may execute shell commands or spawn child processes.
- No tool may read Docker/container logs directly.
- No tool may query Loki directly.
- No tool may query Prometheus directly.
- No tool may write outside the configured bundle root.
- No contract tool may silently switch source. Runtime and offline sources must
  be explicit in the output.
- No write tool may create a partial bundle without returning the changed files
  and validation state.

## Agent Workflow Result Shape

Agent-managed workflow tools expose a compact handoff object named `agent` in
status, report, trace, and evidence-render payloads. Agents should use this as
the default interpretation layer and inspect full evidence only when the handoff
points to a specific gap.

```yaml
name: workflow_result
class: public novice / agent-guided delivery
purpose: Return the compact agent-facing verdict, diagnosis, next action, proof summary, and references for one workflow.
input:
  required:
    workflowId: string
output:
  required:
    workflowId: string
    verdict: needs_input | ready | running | failed | passed | partial
    phase: intake | planning | authoring | validation | deployment | runtime | report | evidence
    summary: string
    diagnosis: object
    nextAction: object
    proof: object
    refs: object
  optional:
    detailRefs: object
sideEffects:
  files: none
  runtime: read-only
allowedSources:
  - In-memory or configured local workflow store
writeScope: none
evidenceValue: Explains the workflow result without hiding the full evidence contract.
failureModes:
  - WORKFLOW_NOT_FOUND
phase: 2
```

Every `agent.nextAction` must identify a concrete MCP tool when the workflow can
advance. Full claim matrices, role checks, operation history, and raw runtime
evidence remain available through `workflow_status`, `workflow_report`, and
`workflow_evidence_render`.

`workflow_result.proof.validation` reports the latest validation attempt and
the Scenario Manager validation state:

```yaml
proof:
  validation:
    status: pass | fail | not-run
    latestLevel: scenario-manager | null
    scenarioManager:
      status: pass | fail | not-run
      code: string | null
      authoritative: boolean
```

Local generation sanity is exposed by `wizard_complete`, `wizard_enrich`, and
`workflow_generate` as `generationSanity`. It is generation diagnostics, not
bundle validation proof. Agents use `workflow_result.nextAction` and
`diagnosis.causes` to decide whether the next step is a bundle patch or an
environment/auth retry.

## Wizard Tool Contracts

`wizard_*` is the novice-facing authoring surface. It stores short-lived session
state in memory and writes bundle files only when `wizard_complete` is called.

```yaml
name: wizard_start
class: public novice
purpose: Start a bundle creation session from an intent and any explicit known answers.
input:
  required:
    intent: string
  optional:
    bundleId: string
    protocol: REST | TCP | SEQUENCE | HTTP
    target: wiremock-local | tcp-mock-local | external
    targetBaseUrl: string
    endpoint: string | { method: string, path: string }
    endpoints: array<string | { method: string, path: string, description?: string, bodyTemplate?: string }>
    requestBody: string
    tcpPayload: string
    ratePerSec: number
    defaultRatePerSec: number
    nftRatePerSec: number
    trafficShape: smoke | ramp_steady | spike | soak | flat
    runDuration: string
    nftDuration: string
    dataSource: SCHEDULER | CSV_DATASET | REDIS_DATASET
    csvColumns: string | string[]
    redisLists: string | string[]
    redisOutput: yes | no | boolean
    auth: none | oauth2_client_credentials | bearer_token_static | basic_auth | api_key | hmac | aws_sig_v4 | iso8583_mac | mtls
    authTokenUrl: string
    authClientId: string
    authSecretSource: env_var | file
    authSecretEnvVar: string
    sutDouble: real_system | wiremock | tcp_mock | wiremock_and_tcp
    mockEndpoints: array<string | object>
    resultRules: yes | no | boolean
    resultCodePattern: string
    successCodes: string | string[]
    performanceObjective: string
    clickhouse: yes_for_nft_only | yes_always | no
    grafanaDashboard: rtt_overview | tx_outcomes | quality | pipeline_observability | none
    docs: yes | no | boolean
output:
  required:
    sessionId: string
    status: gathering | ready
    ready: boolean
    missing: string[]
    errors: string[]
    nextQuestion: object | null
sideEffects:
  files: none
  runtime: in-memory wizard session only
writeScope: none
phase: 1
```

```yaml
name: wizard_answer
class: public novice
purpose: Add one answer to a wizard session and return the next required question.
input:
  required:
    sessionId: string
    questionId: one of the wizard_start optional answer fields above
    answer: any
sideEffects:
  files: none
  runtime: updates in-memory wizard session only
writeScope: none
phase: 1
```

```yaml
name: wizard_summary
class: public novice
purpose: Preview the current wizard plan without writing files.
input:
  required:
    sessionId: string
sideEffects:
  files: none
  runtime: read-only
writeScope: none
phase: 1
```

```yaml
name: wizard_complete
class: public novice
purpose: Generate bundle files from a complete wizard session and run generation sanity checks.
input:
  required:
    sessionId: string
sideEffects:
  files: creates a new bundle directory under the configured bundle root
  runtime: deletes the completed in-memory wizard session
writeScope:
  - <BUNDLES_ROOT>/<bundleId>/**
generatedArtifacts:
  always:
    - scenario.yaml
    - variables.yaml
    - sut/<sutId>/sut.yaml
    - README.md
  whenDocsEnabled:
    - FLOW_DOCUMENT.md
    - CHANGELOG.md
  whenRest:
    - templates/http/<serviceId>/*.yaml
  whenTcp:
    - templates/tcp/<serviceId>/*.yaml
  whenAuthEnabled:
    - authProfiles.yaml
  whenCsvDataset:
    - datasets/sample.csv
  whenRedisDataset:
    - mock-config/redis-state.json
  whenMockTarget:
    - mock-config/wiremock/*.json
    - mock-config/tcp/*.yaml
phase: 1
```

## Scenario Manager Contract Tools

PocketHive MCP exposes Scenario Manager's contract sources directly so wizard
generation can be checked against the same runtime metadata used by the UI and
orchestrator flows.

```yaml
name: scenario_contracts_get
class: public read-only
purpose: Return Scenario Manager-backed contract context for wizard and authoring tools.
input:
  optional:
    scenarioId: string
    includeCapabilities: boolean
    includeTemplates: boolean
    forceRefresh: boolean
    checkFingerprint: boolean
output:
  required:
    source: scenario-manager-api
    baseUrl: string
    endpoints: object
    contract: object
    cache: object
  optional:
    capabilities: array
    templates: array
    scenario: object
sideEffects:
  files: none
  runtime: read-only HTTP calls to Scenario Manager when cache is cold, forced, or fingerprint check is requested
```

`scenario_contracts_get` caches the Scenario Manager authoring contract for the
life of the MCP server process. The first call fetches
`/api/authoring-contract`. Later calls return the cached contract unless:

- `forceRefresh: true` is passed, or
- `checkFingerprint: true` is passed and
  `/api/authoring-contract/fingerprint` differs from the cached fingerprint.

This keeps wizard sessions fast while still giving tools an explicit way to
refresh when Scenario Manager's contract changes.

```yaml
name: scenario_capabilities_get
class: public read-only
purpose: Read worker capability manifests from Scenario Manager `/api/capabilities`.
input:
  optional:
    imageName: string
    tag: string
    all: boolean
sideEffects:
  files: none
  runtime: read-only HTTP call
```

```yaml
name: scenario_templates_catalog
class: public read-only
purpose: Read Scenario Manager `/api/templates`, including defunct bundle status.
sideEffects:
  files: none
  runtime: read-only HTTP call
```

## Bundle Validation Modes

`bundle_validate` is the public static bundle validation surface and always uses
Scenario Manager. Generation sanity checks may run inside authoring tools, but
they are not public validation tools and are not workflow validation evidence.

```yaml
name: bundle_validate
input:
  required:
    bundle: string
  optional:
    validator: scenario-manager-dry-run | scenario-manager-upload
    replaceExisting: boolean
sideEffects:
  scenario-manager-dry-run:
    files: none
    runtime: side-effect-free validation call to Scenario Manager
  scenario-manager-upload:
    files: none in the repo
    runtime: uploads or replaces the bundle through Scenario Manager
```

Use `validator: scenario-manager-dry-run` when the user wants Scenario Manager
to validate the bundle with the live runtime contract and no Scenario Manager
write side effects.

The MCP accepts a dry-run result as authoritative only when Scenario Manager
returns validation evidence containing `supportedScenarioProtocolVersion`,
`scenarioManagerVersion`, and `artifactDigest`. A stale Scenario Manager that
returns the legacy result shape fails explicitly instead of producing an
unversioned authoritative PASS.

Use `validator: scenario-manager-upload` only when the user explicitly wants the
validation step to import or replace the bundle. This mode is intentionally not
hidden behind fallback behavior because it writes to Scenario Manager.

## Capability Negotiation

MCP Apps apply only to HTTP/SSE clients that explicitly declare support. Tools
must have one canonical implementation. Presentation changes by declared client
capability:

| Client capability | Response form |
|---|---|
| JSON tool only | JSON/text MCP response |
| MCP Apps supported | Same tool result plus declared UI resource |

This is not a fallback chain. The server chooses the response form from the
client's declared capability and reports unsupported capabilities explicitly.

## Early MCP App Tool

`evidence_summary` is the only Phase 1.5 App-backed tool.

```yaml
name: evidence_summary
class: public operational
purpose: Return a single read-only evidence summary for a swarm.
input:
  required:
    swarmId: string
  optional:
    includeTapSample: boolean
    scenarioId: string
output:
  required:
    swarmId: string
    scenarioId: string | null
    lifecycle: object
    queues: object
    journal: object
    metrics: object
    mocks: object
    datasets: object
    redis: object
    flow: object
    tapFlow: object | null
    auth: object
    payloads: object
    report: object
    missingEvidence: array
    sources: array
sideEffects:
  files: none
  runtime: read-only
allowedSources:
  - swarm_get
  - debug_journal
  - debug_queues
  - metrics_query
  - runtime_tail_worker_logs when bounded runtime log evidence is requested
  - debug_tap_read when includeTapSample is true
  - mock request tools
  - scenario_get when a template id can be inferred from swarm status or when
    scenarioId is provided
  - Redis token/debug-capture reads for redacted auth and HTTP sequence capture
    evidence
  - dataset_check
writeScope: none
evidenceValue: Summarises what runtime evidence exists and what is missing.
failureModes:
  - SWARM_NOT_FOUND
  - EVIDENCE_SOURCE_UNAVAILABLE
phase: 1.5
```

`report` is a derived read-only acceptance report over the same evidence
sources. It contains:

```yaml
report:
  verdict: pass | partial | fail
  title: string
  generatedAt: ISO-8601 string
  checklist:
    - id: queues.drained
      label: string
      status: pass | partial | fail | unknown | not_applicable
      summary: string
      evidence: array
      gaps: array
  sections:
    - title: string
      rows: array
```

When `includeTapSample=true`, the server must interpret readable debug tap
samples as internal step-flow evidence. The derived `tapFlow` object reports
the interpreted tap sequence, whether it matches the scenario plan, and whether
it agrees with the externally observed WireMock request sequence. The report
checklist includes `tap.flow`; strict runtime proof treats `tap.flow=fail` or
`tap.flow=unknown` as blocking evidence.

The optional MCP App widget renders this exact output as a report. It must not
perform its own runtime calls or contain separate evidence logic. It may offer
the same local light/dark theme toggle used by workflow evidence widgets; that
toggle is presentation-only and must not change the MCP result.

## Agent-Managed Workflow Tools

`workflow_*` is the deterministic control surface for agent-guided delivery
flows. The MCP server owns workflow state, gates, artifact writes, validation,
deployment calls, and evidence capture. The external agent owns source
interpretation, debug strategy, and retry count. Sources may be JMeter, Postman,
OpenAPI, k6, Gatling, cURL, plain instructions, or any other test description
the external agent can convert into a normalized PocketHive plan.

Workflow example lookup is deterministic and read-only. Canonical examples in
repo `scenarios/bundles` are authoritative and are returned before examples in
the active configured bundles root. The active bundles root may add team/user
examples, but it must not silently override canonical examples. Generated
bundles write to the active configured bundles root, not to canonical examples.

IDE plugins may use the workflow MCP surface only for configuration discovery
and status display. They may render `nextQuestions` as unanswered intake items,
but they must not answer those questions or call mutating workflow tools such as
`workflow_update`, `workflow_generate`, `workflow_validate`, `workflow_deploy`,
`workflow_verify`, `workflow_patch`, or `workflow_report`.

App-capable chat clients may render the same workflow evidence through the
read-only `workflow_evidence_render` tool. This tool follows the Apps SDK
decoupled pattern: workflow status/data tools remain usable without a widget,
and the render tool adds `_meta.ui.resourceUri` plus the
`openai/outputTemplate` compatibility alias for clients that can render MCP App
resources. The widget is display-only and must not answer questions, mutate
workflow state, call PocketHive APIs, or invent evidence.

Workflow roles and profiles are deterministic MCP metadata for agent posture,
not permission grants. They tell an external agent which hat to wear and what
to check next. `allowedActions`, missing fields, patch scope, and evidence
gates remain authoritative.

### Normalized Workflow Plan

The external agent submits a normalized plan through `workflow_update`. The MCP
server does not infer source semantics; it validates the submitted plan and
fails closed when the plan is too lossy to generate an accurate bundle.

For source-backed conversions (`jmeter`, `postman`, `openapi`, `k6`,
`gatling`, `curl`, or `other` with a source file), the plan must include
`sourceFidelity.status`. Allowed statuses are:

| Status | Meaning |
|---|---|
| `complete` | The agent claims all relevant source behavior is represented in the normalized plan. |
| `partial-accepted` | Unsupported or intentionally omitted source behavior is listed and accepted by the user or source. |
| `instruction-derived` | The workflow came from plain instructions rather than a formal source artifact. |

If `sourceFidelity.unsupportedConstructs` is non-empty, generation is blocked
until `sourceFidelity.userAcceptedLimitations=true` is provided with `user` or
`source-derived` provenance. The MCP must expose this as a resolvable
`nextQuestions` entry rather than leaving the agent in a dead end.

Endpoint entries may include richer semantics that are preserved where the
current PocketHive bundle format supports them:

```yaml
method: GET | POST | PUT | PATCH | DELETE | HEAD | OPTIONS
path: /absolute/path
callId: stable-call-id
headers: object
query: object
bodyTemplate: string
weight: number
retry: object
continueOnNon2xx: boolean
extracts: array
mock:
  requestHeaders: object
  queryParameters: object
  bodyPatterns: array
  responseBody: object
  jsonBody: object | string
  body: object | string
  response:
    status: number
    jsonBody: object | string
    body: object | string
  status: number
```

Unsupported semantics must be listed in `sourceFidelity.unsupportedConstructs`;
they must not be silently dropped.

For mutating HTTP methods (`POST`, `PUT`, `PATCH`, `DELETE`), generated
WireMock mappings must include body assertions. If the user supplies
`mock.bodyPatterns` or endpoint `bodyPatterns`, those are preserved. Otherwise
the generator derives conservative `matchesJsonPath` assertions from
`bodyTemplate` or the workflow `requestBody`; templated string values such as
`{{customerId}}` assert field presence rather than exact unresolved template
text.

Generated WireMock responses must preserve explicit body aliases supplied as
`responseBody`, `jsonBody`, `body`, `response.jsonBody`, `response.body`,
`mock.responseBody`, `mock.jsonBody`, `mock.body`, `mock.response.jsonBody`, or
`mock.response.body`. If result rules infer a JSON field from
`resultCodePattern` and no explicit response body is supplied, the default mock
body must include that field with a value from `successCodes`. If an explicit
response body is supplied, wizard validation must reject the plan when the
inferred field is missing or has a value outside `successCodes`.

Canonical workflow roles:

| Role id | Purpose |
|---|---|
| `architect` | Clarify intent, scope, assumptions, and unresolved decisions before build. |
| `developer` | Generate or patch PocketHive artifacts within the allowed workflow scope. |
| `tester` | Validate behavior, preserve failed/fixed attempts, and prove acceptance criteria. |
| `security-reviewer` | Review auth, secrets, data sensitivity, unsafe defaults, and external exposure. |
| `performance-testing-specialist` | Review traffic shape, rate, duration, success criteria, and observability. |
| `pockethive-sme` | Check PocketHive-native correctness across bundles, workers, mocks, queues, and evidence. |

Default workflow profiles:

| Profile id | Purpose |
|---|---|
| `novice-test-builder` | Default guided flow for a user asking for a test from source files or instructions. |
| `test-conversion-specialist` | Source-heavy conversion flow for JMeter, Postman, OpenAPI, k6, Gatling, cURL, or similar inputs. |
| `performance-engineer` | Performance-focused flow with stronger traffic, objective, and observability guidance. |
| `runtime-verifier` | Deployment, runtime health, queues, mocks, and evidence-focused flow. |
| `maintainer` | Read/status/configuration-focused profile for MCP and workflow maintenance. |

Teams may provide a schema-validated `workflow-profiles.json`, or set
`PH_WORKFLOW_PROFILES_PATH` to another JSON file. If a custom file is present
and invalid, workflow profile tools fail with `WORKFLOW_PROFILE_CONFIG_INVALID`;
the server must not silently fall back to built-ins.

Workflow sessions are persisted to local JSON by default under the active
bundle root. Set `PH_WORKFLOW_PERSISTENCE=memory` to disable local persistence,
or `PH_WORKFLOW_STORE_PATH` to choose an explicit JSON store path.

HiveMind is optional enrichment only. `workflow_hivemind_enrich` writes a
redacted summary to a configured HiveMind MCP endpoint when
`HIVEMIND_MCP_URL`, `HIVEMIND_BASE_URL`, or `HIVEMIND_API_BASE_URL` is set. It
must not approve actions, satisfy evidence gates, or transition workflow state.

```yaml
name: workflow_examples_list
class: public operational
purpose: List read-only scenario bundle examples, with canonical repo examples before active bundles-root examples.
input:
  optional:
    includeTeamExamples: boolean
output:
  required:
    examples: array
    sourceOrder: array
sideEffects:
  files: none
  runtime: read-only
writeScope: none
phase: 2
```

```yaml
name: workflow_examples_get
class: public operational
purpose: Return one read-only example bundle summary and bounded documentation content.
input:
  required:
    bundleId: string
  optional:
    source: repo-examples | active-bundles-root
sideEffects:
  files: none
  runtime: read-only
writeScope: none
failureModes:
  - WORKFLOW_EXAMPLE_NOT_FOUND
phase: 2
```

```yaml
name: workflow_examples_recommend
class: public operational
purpose: Return deterministic keyword/tag matches from the example library without choosing an archetype for the agent.
input:
  required:
    intent: string
  optional:
    limit: number
sideEffects:
  files: none
  runtime: read-only
writeScope: none
phase: 2
```

```yaml
name: workflow_config_get
class: public operational
purpose: Return sanitized workflow defaults, roles, profiles, and configured roots for plugin/status display.
input: none
sideEffects:
  files: none
  runtime: read-only
writeScope: none
phase: 2
```

```yaml
name: workflow_profiles_list
class: public operational
purpose: List built-in workflow roles and profiles for assistant/plugin display.
input: none
output:
  required:
    defaultProfileId: string
    roles: array
    profiles: array
sideEffects:
  files: none
  runtime: read-only
writeScope: none
phase: 2
```

```yaml
name: workflow_profiles_get
class: public operational
purpose: Return one built-in workflow profile with resolved role details.
input:
  required:
    profileId: string
sideEffects:
  files: none
  runtime: read-only
writeScope: none
failureModes:
  - WORKFLOW_PROFILE_NOT_FOUND
phase: 2
```

```yaml
name: workflow_role_check
class: public novice
purpose: Record one explicit role review/checkpoint for a workflow stage such as Three Amigos.
input:
  required:
    workflowId: string
    stageId: string
    roleId: string
    outcome: pass | risk-accepted | fail
    summary: string
  optional:
    risks: string[]
sideEffects:
  files: updates workflow persistence store when enabled
  runtime: updates workflow session only
writeScope: workflow persistence store only
failureModes:
  - WORKFLOW_REVIEW_STAGE_NOT_FOUND
  - WORKFLOW_REVIEW_ROLE_NOT_REQUIRED
phase: 2
```

```yaml
name: workflow_hivemind_enrich
class: public novice
purpose: Write a redacted workflow memory summary to a configured HiveMind MCP endpoint when available.
input:
  required:
    workflowId: string
sideEffects:
  files: updates workflow persistence store with enrichment result when enabled
  runtime: optional outbound MCP call to HiveMind memory only
writeScope: workflow persistence store only
authority: enrichment-only
phase: 2
```

```yaml
name: workflow_config_validate
class: public operational
purpose: Validate local workflow configuration without creating files or calling runtime services.
input: none
output:
  required:
    ok: boolean
    checks: array
    missing: array
sideEffects:
  files: none
  runtime: read-only
writeScope: none
phase: 2
```

```yaml
name: workflow_list
class: public operational
purpose: List active/restored workflow sessions for status display without returning answers or normalized plans.
input:
  optional:
    state: string
    includeQuestions: boolean
output:
  required:
    workflows: array
    count: number
sideEffects:
  files: none
  runtime: read-only
writeScope: none
phase: 2
```

```yaml
name: workflow_start
class: public novice
purpose: Start an agent-to-pockethive workflow from a local source file or plain instructions.
input:
  required:
    sourceType: string
  optional:
    sourcePath: string
    instructions: string
    profileId: string
    mode: create | modify
    existingBundleId: string
    exampleBundleId: string
    exampleSource: repo-examples | active-bundles-root
    workflowType: agent-to-pockethive
output:
  required:
    workflowId: string
    state: source_ready
    source:
      type: string
      path: string | null
      sha256: string
      bytes: number
    missing: string[]
    nextQuestions: array
    allowedActions: string[]
    profile: object
    activeRole: object
    roleChecklist: array
    mode: create | modify
    example: object | null
    validationIssues: array
    claimMatrix: array
sideEffects:
  files: updates workflow persistence store when enabled
  runtime: creates workflow session
writeScope: workflow persistence store only
failureModes:
  - WORKFLOW_SOURCE_NOT_FOUND
  - WORKFLOW_SOURCE_OUTSIDE_ALLOWED_ROOTS
  - WORKFLOW_SOURCE_REQUIRED
  - WORKFLOW_PROFILE_NOT_FOUND
  - WORKFLOW_MODIFY_BUNDLE_REQUIRED
  - WORKFLOW_MODIFY_BUNDLE_NOT_FOUND
phase: 2
```

```yaml
name: workflow_source_read
class: public novice
purpose: Return bounded source content or instructions for the external agent to interpret.
input:
  required:
    workflowId: string
  optional:
    maxBytes: number
sideEffects:
  files: none
  runtime: read-only
writeScope: none
phase: 2
```

```yaml
name: workflow_update
class: public novice
purpose: Record user answers, answer provenance, and/or the agent-produced normalized conversion plan.
input:
  required:
    workflowId: string
  optional:
    answers: object
    plan: object
    provenance:
      "<fieldPath>":
        source: user | source-derived | agent-inferred | defaulted
        note: string
sideEffects:
  files: updates workflow persistence store when enabled
  runtime: updates in-memory workflow session only
writeScope: workflow persistence store only
mergeSemantics:
  answers: recursive object merge; arrays and scalar values replace
  plan: recursive object merge; arrays and scalar values replace
  provenance: field-path entries replace by key
output:
  required:
    validationIssues: array
    nextQuestions: array
    questionGraph: object
    evidenceContract: array
    blockers: array
    unresolvableBlockers: array
    stuckState: object
failureModes:
  - WORKFLOW_ANSWER_VALIDATION_FAILED
phase: 2
```

```yaml
name: workflow_status
class: public novice
purpose: Return current state, profile, active role, role checklist, missing fields, required next questions, validation issues, claim matrix, evidence gaps, allowed actions, and attempt history.
input:
  required:
    workflowId: string
sideEffects:
  files: none
  runtime: read-only
writeScope: none
phase: 2
```

Agents must treat `nextQuestions` as the authoritative intake checklist. If
questions are present, the agent should ask the user or update the workflow
from explicit source-derived evidence before calling `workflow_generate`,
`workflow_validate`, `workflow_deploy`, or `workflow_verify`.

`nextQuestions` must include every resolvable blocker, not only missing fields.
Question entries include:

```yaml
id: string
questionKind: missing-field | invalid-answer | provenance-confirmation
field: string
questionGroup: intent | target | traffic | data | mock | schema | auth | observability | success | safety | change
order: number
dependsOn: string[]
triggeredBy: object | null
prompt: string
type: string
answerType: string
answerOwner: user-or-source | user-or-source-or-agent
canAgentInfer: boolean
confidence: required-confirmation | agent-fillable
whyAsked: string
resolution:
  tool: workflow_update
  planField: string | null
  provenanceField: string | null
blockedAction: workflow_generate | workflow_patch | null
```

If a present answer is invalid, too vague, unsafe, or backed only by
`agent-inferred`/`defaulted` provenance where the active profile requires
`user` or `source-derived`, that blocker must appear in `nextQuestions`.
This prevents an agent from reaching a blocked state where generation is denied
but no actionable question can be shown to the user.

`questionGraph` is a deterministic read model over the same questions. It must
not ask or answer anything itself. It exposes ordered `nodes` and dependency
`edges` so plugins and agents can present follow-up questions in a stable order
without inventing their own dependencies.

`blockers` lists every current gate before the next mutating action. Each
blocker must include `resolvedBy` with a tool name or `unresolvable: true`.
`unresolvableBlockers` must normally be empty; if it is not empty, the agent
must stop and ask for human help rather than looping.

Agents must also treat `validationIssues` with severity `error` as blocking.
Question validation is deterministic and should catch invalid bundle ids,
invalid URLs, unsafe public-target traffic, missing auth references, vague
success criteria, vague observability goals, incomplete source-fidelity
declarations, unsupported source constructs awaiting acceptance, duplicate
call ids, invalid endpoint methods or paths, and incomplete modification
requests before generation or patching.

External public real-system targets require an explicit safety confirmation
before generation. The confirmation must be backed by `user` or
`source-derived` provenance. For novice requests like "test Google", agents
should prefer a mock-backed scenario unless the user explicitly confirms a
low-rate live/public target.

`evidenceContract` is created before generation and describes the claims the
workflow will later try to prove. It must include required claims such as
generation, Scenario Manager validation, and stakeholder report. Runtime claims
such as workers healthy, queues drained, mock matched requests, traffic shape,
dataset rotation, auth refresh, and observability output are included when
relevant and marked `required` only when the plan/profile requires live proof.
CSV dataset plans produce a sample artifact and authoring evidence only; they
must not claim runtime dataset rotation unless the generated bundle uses a
runtime dataset source such as `REDIS_DATASET`.

`stuckState` detects repeated failed debug/fix attempts with the same failure
code and unchanged artifact fingerprint. The MCP still does not decide how many
iterations the agent may run, but it must surface repeated failures with
resolvable next actions.

Generated bundles include `WORKFLOW_TRACE.json`. The trace links source intent,
example selection, answered fields, provenance, normalized plan, generated
files, claim matrix, evidence contract, and attempt history. It must not embed
inline secrets.

Before `workflow_generate`, the workflow also requires:

- required answer provenance for profile-defined critical fields,
- required role checks for profile-defined pre-generation review stages,
- profile-driven evidence requirements whose `requiredBefore` is
  `workflow_generate`,
- zero blocking answer validation issues.

For the default profiles this makes Three Amigos a real pre-generation
checkpoint rather than advisory text.

```yaml
name: workflow_preview
class: public novice
purpose: Preview generated bundle artifacts or a modify-mode patch scope without writing files.
input:
  required:
    workflowId: string
sideEffects:
  files: none
  runtime: read-only
writeScope: none
phase: 2
```

```yaml
name: workflow_generate
class: public novice
purpose: Generate the bundle after required fields are complete.
input:
  required:
    workflowId: string
sideEffects:
  files: creates a generated bundle under BUNDLES_ROOT
  runtime: records workflow attempt/evidence
writeScope:
  - <BUNDLES_ROOT>/<bundleId>/**
failureModes:
  - WORKFLOW_PLAN_INCOMPLETE
  - WORKFLOW_ANSWER_VALIDATION_FAILED
  - WORKFLOW_PROVENANCE_INCOMPLETE
  - WORKFLOW_ROLE_CHECKS_INCOMPLETE
  - WORKFLOW_EVIDENCE_REQUIREMENTS_INCOMPLETE
  - WORKFLOW_MODIFY_MODE_NO_GENERATE
  - BUNDLE_ALREADY_EXISTS
phase: 2
```

```yaml
name: workflow_validate
class: public novice
purpose: Validate the generated bundle through Scenario Manager and record structured validation evidence.
input:
  required:
    workflowId: string
  optional:
    validator: scenario-manager-dry-run
output:
  required:
    ok: boolean
    code: string
    failureCode: string | null
    authoritative: boolean
    validationLevel: scenario-manager
    suggestedNextActions: string[]
    patchScope: string[]
    evidence: object
sideEffects:
  files: none
  runtime: records workflow attempt/evidence; scenario-manager-dry-run calls Scenario Manager without writes
writeScope: none
phase: 2
```

`validator=scenario-manager-dry-run` is authoritative for the running Scenario
Manager contract and must be used before live deployment when a PocketHive stack
is configured. Workflow sessions keep Scenario Manager validation evidence as
the single workflow validation proof.

Failure codes distinguish artifact failures from environment failures:

- `WORKFLOW_VALIDATION_FAILED` means the generated bundle needs a patch before
  validation can pass. Canonical Scenario Manager findings are exposed in
  `workflow_result.diagnosis.causes` when Scenario Manager returned them.
- `WORKFLOW_EXTERNAL_VALIDATION_FAILED` means Scenario Manager validation could
  not complete against the configured stack.
- `WORKFLOW_ENV_AUTH_FAILED` means PocketHive API auth was rejected, commonly a
  `401`/expired bearer token. `workflow_result.nextAction.tool` must point to
  `env_status` and `patchScope` must be empty because generated bundle files
  are not the first remediation target.

```yaml
name: workflow_deploy
class: public novice
purpose: Convenience deploy path for fast stacks. For slow machines, prefer workflow_deploy_start, workflow_deploy_status, and workflow_deploy_resume.
input:
  required:
    workflowId: string
  optional:
    swarmId: string
    sutId: string
    variablesProfileId: string
    readyTimeoutSec: number
sideEffects:
  files: none
  runtime: Scenario Manager upload/replace and Orchestrator swarm lifecycle calls
writeScope: none
failureModes:
  - WORKFLOW_BUNDLE_NOT_GENERATED
  - WORKFLOW_VALIDATION_REQUIRED
  - WORKFLOW_ENV_AUTH_FAILED
  - WORKFLOW_DEPLOY_FAILED
  - WORKFLOW_DEPLOY_NOT_READY
phase: 2
```

For long-running starts, agents must not rely on one blocking call. Use the
async lifecycle job tools below. Each mutating call advances at most one
bounded lifecycle step and records operation evidence in the workflow session.
Operation status payloads include an audit summary:

```yaml
lastStep:
  id: string
  phase: string
  ok: boolean
  at: string
  code: string | null
  apiActions: array
apiActions:
  - at: string
    phase: string
    action: string
    method: string
    target: string
    result: string
    evidenceKey: string
phaseTimeline:
  - phase: string
    firstSeenAt: string
    lastSeenAt: string
    attempts: number
    status: pending | running | succeeded | failed
    lastCode: string | null
```

The audit summary is intentionally descriptive evidence. The agent still owns
whether to resume, patch, retry, or ask the user; MCP only records what one
explicit lifecycle call did.

```yaml
name: workflow_deploy_start
class: public novice
purpose: Create a resumable deploy operation without waiting for readiness.
input:
  required:
    workflowId: string
  optional:
    swarmId: string
    sutId: string
    variablesProfileId: string
output:
  required:
    operationId: string
    operationType: deploy
    status: running
    phase: upload
    nextPollAfterMs: number
    nextActions: string[]
sideEffects:
  files: none
  runtime: records workflow operation state only
writeScope: none
phase: 2
```

```yaml
name: workflow_deploy_status
class: public novice
purpose: Read current deploy operation state, evidence, elapsed time, and next safe actions.
input:
  required:
    workflowId: string
  optional:
    operationId: string
output:
  required:
    operationId: string
    status: running | succeeded | failed | cancelled
    phase: upload | mock-config | create | wait-ready | start | complete | failed | cancelled
    nextPollAfterMs: number
    evidence: object
    lastStep: object | null
    apiActions: array
    phaseTimeline: array
    nextActions: string[]
sideEffects:
  files: none
  runtime: none
writeScope: none
phase: 2
```

```yaml
name: workflow_deploy_resume
class: public novice
purpose: Advance one deploy lifecycle step. Safe to call repeatedly after slow readiness polling.
input:
  required:
    workflowId: string
  optional:
    operationId: string
output:
  required:
    operationId: string
    status: running | succeeded | failed
    phase: string
    nextPollAfterMs: number
    evidence: object
    lastStep: object | null
    apiActions: array
    phaseTimeline: array
    nextActions: string[]
sideEffects:
  files: none
  runtime: may upload/replace bundle, load mock config, create swarm, poll readiness, or start swarm depending on current phase
writeScope: none
phase: 2
```

Polling semantics are explicit: `status` never advances a lifecycle operation,
and `resume` advances one bounded phase. When the returned operation or
`workflow_result.nextAction` contains `nextPollAfterMs`, agents should wait that
interval before the next `resume` call rather than holding a tool call open.

```yaml
name: workflow_verify
class: public novice
purpose: Convenience runtime proof path for fast stacks. For slow runs, prefer workflow_verify_start, workflow_verify_status, and workflow_verify_resume.
input:
  required:
    workflowId: string
  optional:
    includeTapSample: boolean
    proofMode: accept-partial | strict
sideEffects:
  files: none
  runtime: may pre-arm/read/close a debug tap when includeTapSample is true; records workflow attempt/evidence
writeScope: none
phase: 2
```

`proofMode=accept-partial` preserves the fast debug loop: any `pass` or
`partial` runtime report is accepted while gaps remain visible. Production/live
acceptance must use `proofMode=strict` with `includeTapSample=true`. Strict mode
allows informational unknowns such as optional data-link extraction, but it
fails missing or partial production proof for queue drain, request handling,
flow order, auth when configured, mutating payload body assertions, runtime
payload trace, and debug tap step-flow interpretation.

```yaml
name: workflow_verify_start
class: public novice
purpose: Create a resumable runtime verification operation without blocking for traffic/settle.
input:
  required:
    workflowId: string
  optional:
    swarmId: string
    includeTapSample: boolean
    proofMode: accept-partial | strict
    stopAfterObservation: boolean
output:
  required:
    operationId: string
    operationType: verify
    status: running
    phase: observe
    nextActions: string[]
sideEffects:
  files: none
  runtime: may pre-arm a debug tap when includeTapSample is true; records workflow operation state
writeScope: none
phase: 2
```

```yaml
name: workflow_verify_status
class: public novice
purpose: Read current verification operation state, evidence, elapsed time, and next safe actions.
input:
  required:
    workflowId: string
  optional:
    operationId: string
output:
  required:
    operationId: string
    status: running | succeeded | failed | cancelled
    phase: observe | stop | settle | complete | failed | cancelled
    evidence: object
    lastStep: object | null
    apiActions: array
    phaseTimeline: array
    nextActions: string[]
sideEffects:
  files: none
  runtime: none
writeScope: none
phase: 2
```

```yaml
name: workflow_verify_resume
class: public novice
purpose: Advance one verification lifecycle step. Safe to call repeatedly while traffic or queue drain is slow.
input:
  required:
    workflowId: string
  optional:
    operationId: string
output:
  required:
    operationId: string
    status: running | succeeded | failed
    phase: string
    evidence: object
    lastStep: object | null
    apiActions: array
    phaseTimeline: array
    nextActions: string[]
sideEffects:
  files: none
  runtime: may collect evidence, stop swarm, or poll settlement depending on current phase
writeScope: none
phase: 2
```

```yaml
name: workflow_evidence_render
class: public novice
purpose: Render read-only workflow evidence, questions, claim matrix, role checks, lifecycle operations, and evidence gaps in an MCP App widget.
input:
  required:
    workflowId: string
output:
  required:
    workflowId: string
    state: string
    summary: object
    claimMatrix: array
    evidenceContract: array
    reviewStages: array
    evidenceGaps: array
    operations: object
sideEffects:
  files: none
  runtime: none
writeScope: none
resource:
  uri: ui://pockethive/workflow-evidence-v1.html
  mimeType: text/html;profile=mcp-app
ui:
  localThemeToggle: light | dark
  themePersistence: local widget storage only
metadata:
  _meta.ui.resourceUri: ui://pockethive/workflow-evidence-v1.html
  _meta["openai/outputTemplate"]: ui://pockethive/workflow-evidence-v1.html
phase: 2
```

The App widget may offer local presentation controls such as the light/dark
theme toggle. These controls must not answer workflow questions, mutate
workflow state, call PocketHive APIs, or alter the canonical JSON result.

```yaml
name: workflow_patch
class: public novice
purpose: Apply explicit agent-provided file fixes inside the generated or selected modify-mode workflow bundle.
input:
  required:
    workflowId: string
    changes: array<{ file: string, content: string }>
sideEffects:
  files: writes only inside the generated workflow bundle
  runtime: records workflow attempt
writeScope:
  - <BUNDLES_ROOT>/<bundleId>/**
failureModes:
  - WORKFLOW_PATCH_OUTSIDE_BUNDLE
  - WORKFLOW_ANSWER_VALIDATION_FAILED
phase: 2
```

```yaml
name: workflow_report
class: public novice
purpose: Return canonical JSON workflow evidence and write a stakeholder Markdown handoff.
input:
  required:
    workflowId: string
  optional:
    file: string
sideEffects:
  files: writes report Markdown inside the generated workflow bundle
  runtime: records workflow attempt
writeScope:
  - <BUNDLES_ROOT>/<bundleId>/**
output:
  required:
    claimMatrix: array
    file: string
    report: object
    markdown: string
phase: 2
```

The Markdown report must include a `Lifecycle Operations` section for every
recorded async operation. Each operation includes the last step, phase timeline,
and API actions table so a reviewer can see which official PocketHive paths
were used and which explicit resume call caused them.

The claim matrix is the canonical stakeholder checklist. Each entry contains:

```yaml
id: string
claim: string
status: satisfied | missing | not-run | not-applicable | failed
required: boolean
evidence: array
gap: string | null
```

The default matrix covers bundle existence, answered/gated questions, answer
validation, provenance, role checks, Scenario Manager validation, runtime deployment,
runtime verification, dataset handling, auth handling, mock/live target proof,
observability output, and stakeholder report generation.

## Real-Time Component Control

`component_config_preview` and `component_config_update` provide controlled
real-time worker tuning during debug or test runs. They use the same
Orchestrator evidence and write APIs as the web UI and do not publish AMQP
messages directly.

```yaml
name: component_config_preview
class: public operational
purpose: Preview a merge-with-current-config plan for one running component without sending an update.
input:
  required:
    swarmId: string
    role: string
    instanceId: string
    patch: object
  optional:
    allowEmptyPatch: boolean
    refreshStatus: boolean
    includeMergedConfig: boolean
output:
  required:
    sideEffect: no-config-write
    target: object
    mode: merge-with-current-config
    currentConfig: object
    patchSummary: object
    mergedConfigSummary: object
sideEffects:
  files: none
  runtime: may request fresh status snapshots; does not publish config-update
allowedSources:
  - PocketHive Orchestrator: POST /api/control-plane/refresh
  - PocketHive Orchestrator: GET /api/swarms/{swarmId}/journal/page
  - PocketHive control-plane event stream: event.metric.status-full.{swarmId}.{role}.{instance}
writeScope: none
evidenceValue: Shows exactly which current config snapshot would be used and which top-level keys the merged config contains.
failureModes:
  - CURRENT_CONFIG_UNAVAILABLE
  - EMPTY_PATCH_REJECTED
  - ORCHESTRATOR_UNAVAILABLE
phase: 1.6
```

```yaml
name: component_config_update
class: public operational
purpose: Send a real-time config-update signal to one running component through Orchestrator.
input:
  required:
    swarmId: string
    role: string
    instanceId: string
    patch: object
  optional:
    idempotencyKey: string
    notes: string
    allowEmptyPatch: boolean
    refreshStatus: boolean
output:
  required:
    accepted: boolean
    source: orchestrator-api
    endpoint: string
    target: object
    mode: merge-with-current-config
    currentConfig: object
    patchSummary: object
    mergedConfigSummary: object
    response: object
    watch: object
    evidenceNext: array
sideEffects:
  files: none
  runtime: publishes signal.config-update through Orchestrator
allowedSources:
  - PocketHive Orchestrator: POST /api/control-plane/refresh
  - PocketHive Orchestrator: GET /api/swarms/{swarmId}/journal/page
  - PocketHive control-plane event stream: event.metric.status-full.{swarmId}.{role}.{instance}
  - PocketHive Orchestrator: POST /api/components/{role}/{instance}/config
writeScope:
  - runtime component config for the targeted swarm/role/instance
evidenceValue: Proves Orchestrator accepted the control-plane update request and gives watch topics for outcome/alert evidence.
failureModes:
  - CURRENT_CONFIG_UNAVAILABLE
  - EMPTY_PATCH_REJECTED
  - ORCHESTRATOR_UNAVAILABLE
phase: 1.6
```

The tool must read the latest exact `status-full` config for the target
component before sending an update. It may use journaled Orchestrator evidence
when available, or bind to the control-plane status stream and request a fresh
status refresh when worker config snapshots are only available on the stream.
It deep-merges the requested `patch` into that current config and sends the
merged config as the Orchestrator request patch. If no current config is
available for the exact `swarmId`, `role`, and `instanceId`, the tool must fail
closed with `CURRENT_CONFIG_UNAVAILABLE`.
The preview and update tools must share the same merge logic, and that logic
must be covered by automated tests.

Agents must treat `accepted=true` as dispatch evidence only. To prove the
component applied the update, follow the returned watch topics, read
`debug_journal`, or inspect status / `metrics_query` output for the targeted
component.

`debug_config_update` remains as a compatibility alias, but new workflows should
prefer `component_config_update`.
