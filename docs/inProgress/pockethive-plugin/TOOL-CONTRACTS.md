# PocketHive MCP Tool Contracts

## Status
`IN PROGRESS`

This document defines the contract shape every PocketHive MCP tool must have.
It is intentionally mechanical so agents can implement, review, and test tools
without guessing.

## Tool Classes

| Class | Audience | Naming | May write files? | May call runtime APIs? |
|---|---|---|---|---|
| Public novice | End users through AI chat | `wizard.*` | Only on `wizard.complete` | Yes, where declared |
| Public operational | IDE users and agents | `bundle.*`, `scenario.*`, `swarm.*`, `component.*`, `debug.*`, `mock.*`, `dataset.*`, `contract.*`, `health.*`, `context.*`, `env.*` | Only where declared | Yes |
| Advanced authoring | Experienced agents | `session.*`, `pipeline.*`, `bee.*`, `template.*`, `variables.*`, `sut.*`, `traffic-policy.*`, `plan.*` | Yes, guarded bundle writes | Usually no, unless declared |
| Internal helper | MCP implementation only | private functions/modules | As needed internally | As needed internally |

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
name: tool.name
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
| `context.*` | Public operational | `MCP-SERVER.md` | In-memory context/settings signal |
| `env.*` | Public operational | `MCP-SERVER.md`, `CONFIG.md` | Settings signal, restart required where declared |
| `health.check` | Public operational | `MCP-SERVER.md` | Read-only |
| `contract.*` | Public operational | `MCP-IMPROVEMENT-SPEC.md` | Read-only |
| `bundle.list/read` | Public operational | `MCP-SERVER.md` | Read-only |
| `bundle.check/validate/validate.result` | Public operational | `MCP-IMPROVEMENT-SPEC.md` | Read-only or validation job state |
| `bundle.diff` | Public operational | `MCP-IMPROVEMENT-SPEC.md` | Read-only |
| `bundle.docs.*` | Advanced authoring | `MCP-IMPROVEMENT-SPEC.md` | Guarded bundle doc writes |
| `scenario.*` | Public operational | `MCP-SERVER.md` | Runtime scenario import/read |
| `scenario.contracts.get`, `scenario.capabilities.get`, `scenario.templates.catalog` | Public read-only contract discovery | Scenario Manager API | Read-only HTTP calls |
| `swarm.*` | Public operational | `MCP-SERVER.md` | Runtime swarm lifecycle |
| `component.config-preview` | Public operational | `MCP-SERVER.md`, `ORCHESTRATOR-REST.md` | Read-only runtime config merge preview |
| `component.config-update` | Public operational | `MCP-SERVER.md`, `ORCHESTRATOR-REST.md` | Runtime control-plane config update |
| `debug.*` | Public operational | `MCP-SERVER.md`, `EVIDENCE.md` | Runtime read/tap lifecycle only |
| `evidence.summary` | Public operational | `EVIDENCE.md`, `MCP-APPS.md` | Read-only aggregate evidence model |
| `workflow.*` | Public novice / agent-guided delivery | This document | Session state; generated bundle writes; optional runtime calls |
| `mock.*` | Public operational | `MCP-IMPROVEMENT-SPEC.md` | Mock admin state and optional bundle mock-config writes |
| `dataset.*` | Public operational | `MCP-IMPROVEMENT-SPEC.md` | Dataset seed/check/save |
| `wizard.*` | Public novice | `BUNDLE-WIZARD.md`, `MCP-IMPROVEMENT-SPEC.md` | Session state; files only on complete |
| `session.*` | Advanced authoring | `BUNDLE-WIZARD.md` | Authoring session state and guarded bundle writes |
| `pipeline.*`, `bee.*`, `template.*`, `variables.*`, `sut.*`, `traffic-policy.*`, `plan.*` | Advanced authoring | `BUNDLE-WIZARD.md` | Guarded bundle writes |

## Global Tool Rules

- No tool may execute shell commands or spawn child processes.
- No tool may read Docker/container logs directly.
- No tool may query Loki directly.
- No tool may write outside the configured bundle root.
- No contract tool may silently switch source. Runtime and offline sources must
  be explicit in the output.
- No write tool may create a partial bundle without returning the changed files
  and validation state.

## Wizard Tool Contracts

`wizard.*` is the novice-facing authoring surface. It stores short-lived session
state in memory and writes bundle files only when `wizard.complete` is called.

```yaml
name: wizard.start
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
name: wizard.answer
class: public novice
purpose: Add one answer to a wizard session and return the next required question.
input:
  required:
    sessionId: string
    questionId: one of the wizard.start optional answer fields above
    answer: any
sideEffects:
  files: none
  runtime: updates in-memory wizard session only
writeScope: none
phase: 1
```

```yaml
name: wizard.summary
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
name: wizard.complete
class: public novice
purpose: Generate bundle files from a complete wizard session and run bundle.check.
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
name: scenario.contracts.get
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

`scenario.contracts.get` caches the Scenario Manager authoring contract for the
life of the MCP server process. The first call fetches
`/api/authoring-contract`. Later calls return the cached contract unless:

- `forceRefresh: true` is passed, or
- `checkFingerprint: true` is passed and
  `/api/authoring-contract/fingerprint` differs from the cached fingerprint.

This keeps wizard sessions fast while still giving tools an explicit way to
refresh when Scenario Manager's contract changes.

```yaml
name: scenario.capabilities.get
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
name: scenario.templates.catalog
class: public read-only
purpose: Read Scenario Manager `/api/templates`, including defunct bundle status.
sideEffects:
  files: none
  runtime: read-only HTTP call
```

## Bundle Validation Modes

`bundle.check` is the in-process structural check. `bundle.validate` requires an
explicit validator:

```yaml
name: bundle.validate
input:
  required:
    bundle: string
  optional:
    validator: local-structural | scenario-manager-dry-run | scenario-manager-upload
    replaceExisting: boolean
sideEffects:
  local-structural:
    files: none
    runtime: validation job state only
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

`evidence.summary` is the only Phase 1.5 App-backed tool.

```yaml
name: evidence.summary
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
    auth: object
    payloads: object
    report: object
    missingEvidence: array
    sources: array
sideEffects:
  files: none
  runtime: read-only
allowedSources:
  - swarm.get
  - debug.journal
  - debug.queues
  - debug.prometheus
  - debug.tap/read when includeTapSample is true
  - mock request tools
  - scenario.get when a template id can be inferred from swarm status or when
    scenarioId is provided
  - Redis token/debug-capture reads for redacted auth and HTTP sequence capture
    evidence
  - dataset.check
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

The optional MCP App widget renders this exact output as a report. It must not
perform its own runtime calls or contain separate evidence logic.

## Agent-Managed Workflow Tools

`workflow.*` is the deterministic control surface for agent-guided delivery
flows. The MCP server owns workflow state, gates, artifact writes, validation,
deployment calls, and evidence capture. The external agent owns source
interpretation, debug strategy, and retry count. Sources may be JMeter, Postman,
OpenAPI, k6, Gatling, cURL, plain instructions, or any other test description
the external agent can convert into a normalized PocketHive plan.

IDE plugins may use the workflow MCP surface only for configuration discovery
and status display. They may render `nextQuestions` as unanswered intake items,
but they must not answer those questions or call mutating workflow tools such as
`workflow.update`, `workflow.generate`, `workflow.validate`, `workflow.deploy`,
`workflow.verify`, `workflow.patch`, or `workflow.report`.

```yaml
name: workflow.config.get
class: public operational
purpose: Return sanitized workflow defaults and configured roots for plugin/status display.
input: none
sideEffects:
  files: none
  runtime: read-only
writeScope: none
phase: 2
```

```yaml
name: workflow.config.validate
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
name: workflow.list
class: public operational
purpose: List in-memory workflow sessions for status display without returning answers or normalized plans.
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
name: workflow.start
class: public novice
purpose: Start an agent-to-pockethive workflow from a local source file or plain instructions.
input:
  required:
    sourceType: string
  optional:
    sourcePath: string
    instructions: string
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
sideEffects:
  files: none
  runtime: in-memory workflow session only
writeScope: none
failureModes:
  - WORKFLOW_SOURCE_NOT_FOUND
  - WORKFLOW_SOURCE_OUTSIDE_ALLOWED_ROOTS
  - WORKFLOW_SOURCE_REQUIRED
phase: 2
```

```yaml
name: workflow.source.read
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
name: workflow.update
class: public novice
purpose: Record user answers and/or the agent-produced normalized conversion plan.
input:
  required:
    workflowId: string
  optional:
    answers: object
    plan: object
sideEffects:
  files: none
  runtime: updates in-memory workflow session only
writeScope: none
phase: 2
```

```yaml
name: workflow.status
class: public novice
purpose: Return current state, missing fields, required next questions, evidence gaps, allowed actions, and attempt history.
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
required questions are present, the agent should ask the user or update the
workflow with an agent-derived answer before calling `workflow.generate`,
`workflow.validate`, `workflow.deploy`, or `workflow.verify`.

```yaml
name: workflow.preview
class: public novice
purpose: Preview generated bundle artifacts without writing files.
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
name: workflow.generate
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
  - BUNDLE_ALREADY_EXISTS
phase: 2
```

```yaml
name: workflow.validate
class: public novice
purpose: Validate the generated bundle and record structured validation evidence.
input:
  required:
    workflowId: string
  optional:
    validator: local-structural | scenario-manager-dry-run
output:
  required:
    ok: boolean
    code: string
    evidence: object
sideEffects:
  files: none
  runtime: records workflow attempt/evidence; scenario-manager-dry-run calls Scenario Manager without writes
writeScope: none
phase: 2
```

```yaml
name: workflow.deploy
class: public novice
purpose: Deploy the generated bundle and create/wait/start a swarm through official PocketHive APIs.
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
phase: 2
```

```yaml
name: workflow.verify
class: public novice
purpose: Collect runtime proof for the workflow swarm from existing evidence sources.
input:
  required:
    workflowId: string
  optional:
    includeTapSample: boolean
sideEffects:
  files: none
  runtime: records workflow attempt/evidence
writeScope: none
phase: 2
```

```yaml
name: workflow.patch
class: public novice
purpose: Apply explicit agent-provided file fixes inside the generated workflow bundle.
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
phase: 2
```

```yaml
name: workflow.report
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
phase: 2
```

## Real-Time Component Control

`component.config-preview` and `component.config-update` provide controlled
real-time worker tuning during debug or test runs. They use the same
Orchestrator evidence and write APIs as the web UI and do not publish AMQP
messages directly.

```yaml
name: component.config-preview
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
name: component.config-update
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
`debug.journal`, or inspect status/metrics for the targeted component.

`debug.config-update` remains as a compatibility alias, but new workflows should
prefer `component.config-update`.
