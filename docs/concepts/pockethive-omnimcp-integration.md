# PocketHive × OmniMCP — Integration Proposal

## The idea in one sentence

Register the PocketHive MCP server as a **governed local-edge capability** inside
OmniMCP so that an AI sandboxed in a VM can drive PocketHive swarms, author scenario
bundles, and inspect live test results — but only through OmniMCP's execution-ticket
and policy gate, with every action audited and evidence-linked.

---

## Why this pairing makes sense

OmniMCP solves the governance problem: AI can reason freely but can only *act* through
a ticketed, policy-gated path. PocketHive solves the load-test execution problem: it
runs realistic, scenario-driven traffic against real or mocked systems and produces
observable, measurable outcomes.

Together they give you:

- AI that can **propose, author, and run** a load scenario
- a governance layer that **approves, bounds, and audits** every action before it touches
  the stack
- observable **evidence** (queue depths, Prometheus metrics, journal events, tap payloads)
  that feeds back into the AI's next decision — all through the same governed path

The VM-sandboxed AI model has **no direct network access**. Its only way out is through
OmniMCP's MCP gateway. PocketHive becomes a governed capability behind that gateway,
not a raw tool the AI can call freely.

---

## Architecture fit

### Where PocketHive sits in OmniMCP's plane model

| OmniMCP plane | PocketHive component | Notes |
|---|---|---|
| Execution plane | `swarm.create/start/stop/remove`, `scenario.deploy`, `bundle.scaffold` | Mutating — require execution tickets |
| Shared capability plane | `swarm.get`, `swarm.list`, `debug.queues`, `debug.journal`, `debug.prometheus` | Read-only — policy-gated but no ticket needed |
| Execution plane | `debug.tap`, `debug.tap.read`, `debug.tap.close` | Short-lived read with side-effect (creates tap resource) — ticket scoped to tap lifecycle |
| Shared capability plane | `scenario.list`, `scenario.get`, `bundle.list`, `bundle.read` | Read-only catalogue queries |
| Execution plane | `bundle.validate` | Local JVM invocation — ticket required, output redacted to pass/fail + error summary |
| Admin/config plane | `env.add`, `env.remove`, `env.switch`, `context.set-bundles-root` | Not default discoverable; admin-only surface |

### Capability scope classification

All PocketHive tools map to `LOCAL_EDGE` in OmniMCP's capability contract because:
- the PocketHive stack runs on the local workstation or a local VM
- the MCP server is spawned locally
- no production systems are touched in Phase 1

When PocketHive targets a remote NFT stack (`POCKETHIVE_BASE_URL` pointing off-host),
the capability scope shifts to `ENTERPRISE` and the shared-capability-plane rules apply.

---

## The execution ticket model applied to PocketHive

OmniMCP's execution ticket contract maps cleanly onto PocketHive's mutating operations:

```json
{
  "ticketId": "uuid",
  "requestId": "uuid",
  "actorId": "ai-agent-vm-01",
  "commandClass": "POCKETHIVE_SWARM_LIFECYCLE",
  "argumentsProfile": {
    "swarmId": "load-test-001",
    "templateId": "pcs-auth-csv",
    "sutId": "tcp-mock-local"
  },
  "workingDirectoryScope": "bundles/pcs-auth-csv",
  "environment": "local",
  "expiresAt": "ISO-8601 +5min",
  "singleUse": true,
  "obligations": ["AUDIT", "STORE_EVIDENCE"]
}
```

### Command classes for PocketHive

| commandClass | Covers | Risk level |
|---|---|---|
| `POCKETHIVE_BUNDLE_READ` | `bundle.list`, `bundle.read`, `scenario.list`, `scenario.get` | low |
| `POCKETHIVE_BUNDLE_VALIDATE` | `bundle.validate` + poll | low |
| `POCKETHIVE_BUNDLE_SCAFFOLD` | `bundle.scaffold` | medium — writes files |
| `POCKETHIVE_BUNDLE_DEPLOY` | `scenario.deploy` | medium — modifies SM state |
| `POCKETHIVE_SWARM_LIFECYCLE` | `swarm.create`, `swarm.start`, `swarm.stop`, `swarm.remove` | high — spins up containers |
| `POCKETHIVE_SWARM_READ` | `swarm.get`, `swarm.list`, `debug.queues`, `debug.journal`, `debug.prometheus` | low |
| `POCKETHIVE_DEBUG_TAP` | `debug.tap`, `debug.tap.read`, `debug.tap.close` | low |
| `POCKETHIVE_CONFIG_UPDATE` | `debug.config-update` | medium — mutates running worker |

---

## Integration architecture

```
+----------------------------------------------------------+
|  AI Agent (sandboxed VM)                                 |
|  Reasoning: Amazon Q / Claude / Copilot                  |
|  Only outbound path: OmniMCP MCP Gateway (HTTP/SSE)      |
+---------------------------+------------------------------+
                            |  MCP request envelope
                            v
+---------------------------+------------------------------+
|  OmniMCP Gateway (control plane)                         |
|                                                          |
|  1. Normalise client surface: CODEX_LOCAL                |
|  2. Policy evaluation → ALLOW / DENY / REQUIRE_APPROVAL  |
|  3. Capability lookup → pockethive.* capability pack     |
|  4. Issue execution ticket (for mutating ops)            |
|  5. Route to PocketHive local-edge adapter               |
|  6. Normalise + redact output (summary-first)            |
|  7. Record evidence obligation                           |
+---------------------------+------------------------------+
                            |  execution ticket + args
                            v
+---------------------------+------------------------------+
|  PocketHive Local-Edge Adapter                           |
|  (OmniMCP execution plane module)                        |
|                                                          |
|  - Validates ticket (id, expiry, commandClass, scope)    |
|  - Calls tools/pockethive-mcp/server.mjs via stdio       |
|  - Maps commandClass → allowed tool set                  |
|  - Redacts raw payloads → summary + evidence refs        |
|  - Returns normalised result to gateway                  |
+---------------------------+------------------------------+
                            |  stdio / HTTP
                            v
+---------------------------+------------------------------+
|  tools/pockethive-mcp/server.mjs                         |
|  (existing MCP server, unchanged)                        |
|                                                          |
|  bundle.*, scenario.*, swarm.*, debug.*, mock.*          |
+---------------------------+------------------------------+
                            |
                            v
+---------------------------+------------------------------+
|  PocketHive Stack                                        |
|  Orchestrator · Scenario Manager · RabbitMQ              |
|  WireMock · TCP Mock · Prometheus                        |
+----------------------------------------------------------+
```

---

## What the AI can and cannot do

### Can do (with ticket)
- scaffold a new bundle from a description
- validate the bundle offline
- deploy the bundle to the Scenario Manager
- create and start a swarm
- read swarm status, queue depths, journal events
- tap message payloads to verify data flow
- query Prometheus metrics
- stop and remove a swarm

### Cannot do (blocked by policy)
- call PocketHive APIs directly (no network path out of VM)
- write arbitrary files outside the approved bundle workspace
- switch environments or modify MCP server config (admin-only surface)
- run `debug.docker-logs` (requires Docker socket — not available in VM)
- push to git (no git credentials in VM; git operations require a separate ticket class)
- access production stacks (environment classification blocks it)

---

## Skill definition: `pockethive.run-load-scenario`

This is the primary skill the AI uses. It maps to OmniMCP's skill contract:

```json
{
  "skillId": "pockethive.run-load-scenario",
  "intent": "load_test_execution",
  "allowedCapabilities": [
    "pockethive.bundle.scaffold",
    "pockethive.bundle.validate",
    "pockethive.bundle.deploy",
    "pockethive.swarm.lifecycle",
    "pockethive.swarm.read",
    "pockethive.debug.tap"
  ],
  "defaultContextProfile": "STANDARD",
  "requiredContext": [
    "bundle_id",
    "target_sut",
    "expected_rate",
    "test_duration",
    "acceptance_criteria"
  ],
  "approvalMode": "POLICY_DRIVEN",
  "evidenceRequired": true,
  "deterministicFallback": true
}
```

The skill runner orchestrates the full TDD cycle:
1. `bundle.scaffold` → create bundle
2. `bundle.validate` → gate on pass
3. `scenario.deploy` → load into SM
4. `swarm.create` + `swarm.wait-ready` + `swarm.start`
5. Poll `debug.queues` + `debug.prometheus` until acceptance criteria met or timeout
6. `debug.tap` → sample payloads for evidence
7. `swarm.stop` + `swarm.remove`
8. Return structured evidence summary

---

## Evidence output

After the skill completes, OmniMCP's evidence plane records:

```json
{
  "evidenceEventId": "uuid",
  "requestId": "uuid",
  "artifactRefs": [
    "bundles/pcs-auth-csv/scenario.yaml",
    "evidence/load-test-001/validation-result.json",
    "evidence/load-test-001/prometheus-snapshot.json",
    "evidence/load-test-001/tap-samples.json",
    "evidence/load-test-001/journal-events.json"
  ],
  "summary": "Swarm load-test-001 ran for 5m at 50 msg/s. 99.2% success rate. p95 latency 142ms. Acceptance criteria met.",
  "classification": "internal"
}
```

The AI sees only the summary and refs — not raw payloads. Raw tap samples and journal
events are stored as evidence artifacts, accessible to human reviewers but not
automatically widened into the model context.

---

## OmniMCP MCP_INVENTORY.md entry

Add this row to the PocketHive capability in `docs/MCP_INVENTORY.md`:

| Surface | Status | Local or Shared | Wrapped by API-first service | Primary purpose | Surface class | Notes | Gateway target state |
|---|---|---|---|---|---|---|---|
| PocketHive Local-Edge MCP | Planned | Local | No — direct stdio to pockethive-mcp | Drive scenario bundle authoring, swarm lifecycle, and live test observability | Read / Mutate / Act | Mutating ops require execution tickets; read ops are policy-gated; output is summary-first with evidence refs; no production access in Phase 1 | Local-edge adapter behind gateway |

---

## Capability registry entries (sample)

```json
[
  {
    "toolName": "pockethive.swarm.lifecycle",
    "capabilityScope": "LOCAL_EDGE",
    "riskLevel": "high",
    "readOnly": false,
    "destructive": true,
    "allowedRoles": ["qa", "developer"],
    "allowedStages": ["act"],
    "requiresApproval": false,
    "auditRequired": true,
    "evidenceRequired": true
  },
  {
    "toolName": "pockethive.swarm.read",
    "capabilityScope": "LOCAL_EDGE",
    "riskLevel": "low",
    "readOnly": true,
    "destructive": false,
    "allowedRoles": ["qa", "developer", "architect"],
    "allowedStages": ["sense", "decide", "act", "visualize"],
    "requiresApproval": false,
    "auditRequired": false,
    "evidenceRequired": false
  },
  {
    "toolName": "pockethive.bundle.scaffold",
    "capabilityScope": "LOCAL_EDGE",
    "riskLevel": "medium",
    "readOnly": false,
    "destructive": false,
    "allowedRoles": ["qa", "developer"],
    "allowedStages": ["act"],
    "requiresApproval": false,
    "auditRequired": true,
    "evidenceRequired": true
  }
]
```

---

## What needs to be built

### In OmniMCP (new work)

1. **PocketHive capability pack** — visibility/discovery bundle for all `pockethive.*`
   tools, filtered by role, stage, and environment.

2. **PocketHive local-edge adapter** — an OmniMCP execution-plane module that:
   - validates execution tickets before calling the MCP server
   - maps `commandClass` → allowed tool set (enforces the table above)
   - spawns `tools/pockethive-mcp/server.mjs` via stdio with env vars from OmniMCP config
   - redacts raw payloads and returns summary + evidence refs

3. **`pockethive.run-load-scenario` skill** — orchestration recipe in `skills/`
   following the TDD cycle above.

4. **Evidence templates** — `docs/FEATURE_EVIDENCE_*.md` artifacts for load test runs.

5. **MCP_INVENTORY.md entry** — register the surface (row above).

6. **Capability registry entries** — JSON entries for all `pockethive.*` tools.

### In PocketHive (no changes needed)

The existing `tools/pockethive-mcp/server.mjs` is consumed as-is. The OmniMCP adapter
spawns it over stdio and injects env vars. The MCP server has no knowledge of OmniMCP —
it just responds to tool calls.

The only PocketHive-side prerequisite is that `server.mjs` accepts all config via
`process.env` at spawn time (already done in the Phase 1 migration work).

---

## Context efficiency rules for PocketHive outputs

PocketHive tools can return large payloads (queue lists, journal pages, tap samples,
Prometheus query results). The adapter must enforce OmniMCP's context efficiency rules:

| Tool | Default profile | Model-visible output |
|---|---|---|
| `swarm.get` | MINIMAL | status, health, bee count — no raw envelope |
| `debug.queues` | MINIMAL | queue name, depth, consumers — no raw RabbitMQ metadata |
| `debug.journal` | STANDARD | top 5 events, summary of errors — no raw JSON payloads |
| `debug.tap.read` | STANDARD | 3 sample summaries (headers + step count) — no raw body |
| `debug.prometheus` | MINIMAL | metric name, latest value, labels — no raw Prometheus response |
| `bundle.validate` | MINIMAL | PASS / FAIL + first error message — no full stack trace |
| `swarm.list` | MINIMAL | swarm IDs, statuses — no per-worker detail |

Raw detail is stored as evidence artifacts and available on explicit escalation
(`contextProfile: DEEP`) only.

---

## VM sandbox boundary summary

```
VM boundary
  ├── AI agent process (Amazon Q / Claude / Copilot)
  │     └── only outbound: HTTP/SSE to OmniMCP gateway
  │
  └── OmniMCP gateway process
        ├── policy engine (deterministic, no AI)
        ├── execution ticket issuer
        └── PocketHive local-edge adapter
              └── stdio → pockethive-mcp/server.mjs
                    └── HTTP → PocketHive stack
                          (Orchestrator, SM, RabbitMQ, WireMock, TCP Mock)
```

The AI cannot reach the PocketHive stack directly. It cannot reach the filesystem
directly. It cannot reach git directly. Every action is:
1. classified by the gateway
2. ticket-issued for mutating ops
3. executed by the adapter within the ticket scope
4. summarised and evidence-linked before the result reaches the model

---

## Resolved decisions

All five open questions are now closed. These decisions are authoritative for
implementation and must be reflected in the capability registry, policy rules,
execution ticket contracts, and skill definitions.

---

### Decision 1 — Approval threshold for swarm lifecycle

**Decision: auto-allow for `environment: local`, `REQUIRE_APPROVAL` for all external environments.**

Policy rule:

```json
{
  "rule": "pockethive.swarm.lifecycle.approval",
  "condition": { "environment": ["nft", "uat", "staging", "prod"] },
  "decision": "REQUIRE_APPROVAL",
  "reasonCode": "EXTERNAL_ENVIRONMENT_SWARM_MUTATION"
}
```

```json
{
  "rule": "pockethive.swarm.lifecycle.local",
  "condition": { "environment": ["local"] },
  "decision": "ALLOW",
  "reasonCode": "LOCAL_AUTO_ALLOW"
}
```

Capability registry update — `pockethive.swarm.lifecycle`:

```json
{
  "requiresApproval": false,
  "approvalOverrideByEnvironment": {
    "nft": true,
    "uat": true,
    "staging": true,
    "prod": true
  }
}
```

The gateway evaluates environment classification from the request envelope's
`target.environment` field. If the AI does not supply it, the gateway defaults
to `REQUIRE_APPROVAL` (deny-closed on ambiguity).

---

### Decision 2 — Bundle workspace scope

**Decision: AI may scaffold bundles anywhere within the VM-local `BUNDLES_ROOT`.**

Because the AI is sandboxed inside a VM, the entire `BUNDLES_ROOT` directory is
within the VM's local filesystem boundary. No additional subdirectory restriction
is needed — the VM boundary is the containment.

Execution ticket `workingDirectoryScope` is set to the full `BUNDLES_ROOT` path:

```json
{
  "commandClass": "POCKETHIVE_BUNDLE_SCAFFOLD",
  "workingDirectoryScope": "${BUNDLES_ROOT}",
  "environment": "local"
}
```

The adapter enforces that all file writes from `bundle.scaffold` resolve inside
`workingDirectoryScope` (path traversal guard). No writes outside `BUNDLES_ROOT`
are permitted regardless of what the AI requests.

---

### Decision 3 — Tap TTL and evidence retention

**Decision: the adapter owns tap snapshot retention, driven by a configurable retention policy.**

The skill runner is responsible for orchestration logic, not storage. The adapter
is the right owner because it is the only component that knows when a tap is about
to expire (it tracks the `ttlSeconds` from the `debug.tap` response).

Retention policy (configurable, defaults shown):

```json
{
  "tapRetention": {
    "snapshotBeforeExpirySeconds": 30,
    "maxSamplesPerSnapshot": 10,
    "maxBodyCharsPerSample": 500,
    "storeAs": "evidence-artifact",
    "artifactPathTemplate": "evidence/${requestId}/tap-${tapId}-snapshot.json",
    "retentionDays": 30
  }
}
```

Flow:
1. Adapter creates tap via `debug.tap`, records `tapId` and `createdAt`.
2. Adapter schedules a snapshot at `ttlSeconds - snapshotBeforeExpirySeconds`.
3. At snapshot time, adapter calls `debug.tap.read` with `drain: maxSamplesPerSnapshot`.
4. Adapter truncates body fields to `maxBodyCharsPerSample`, writes artifact.
5. Adapter calls `debug.tap.close`.
6. Artifact reference is added to the evidence event for the current request.

The skill runner receives the artifact reference in the evidence summary — it never
handles raw tap data directly.

---

### Decision 4 — Config-update risk classification

**Decision: a single session-scoped execution ticket covers all `debug.config-update`
calls within one test session.**

This enables AI-driven adaptive load shaping (e.g. ramp rate up/down mid-test)
without requiring a new approval or ticket per call.

Session ticket shape:

```json
{
  "commandClass": "POCKETHIVE_CONFIG_UPDATE",
  "argumentsProfile": {
    "swarmId": "${swarmId}",
    "allowedRoles": ["generator", "moderator"],
    "allowedPatchKeys": ["inputs.scheduler.ratePerSec", "enabled", "mode.ratePerSec"]
  },
  "workingDirectoryScope": "${BUNDLES_ROOT}",
  "environment": "local",
  "expiresAt": "swarm stop time + 5min",
  "singleUse": false,
  "maxUses": 50,
  "obligations": ["AUDIT"]
}
```

Key constraints enforced by the adapter:
- `swarmId` in every call must match the ticket's `swarmId` — no cross-swarm updates
- only `allowedRoles` may be targeted — no swarm-controller or postprocessor patches
- only `allowedPatchKeys` may appear in the patch body — no arbitrary config mutation
- ticket expires when the swarm stops (adapter invalidates it on `swarm.stop` or `swarm.remove`)

---

### Decision 5 — Skill-first with read-only tools directly discoverable

**Decision: `pockethive.run-load-scenario` skill is the primary AI entry point.
Read-only tools are directly discoverable. Mutating tools are skill-only.**

Discovery rules by surface class:

| Tool group | Discoverable directly? | Requires skill? |
|---|---|---|
| `POCKETHIVE_BUNDLE_READ` | Yes — all roles, all stages | No |
| `POCKETHIVE_SWARM_READ` | Yes — all roles, all stages | No |
| `POCKETHIVE_DEBUG_TAP` | Yes — qa/developer, act/visualize stages | No |
| `POCKETHIVE_BUNDLE_VALIDATE` | Yes — qa/developer, act stage | No |
| `POCKETHIVE_BUNDLE_SCAFFOLD` | No — skill-only | Yes |
| `POCKETHIVE_BUNDLE_DEPLOY` | No — skill-only | Yes |
| `POCKETHIVE_SWARM_LIFECYCLE` | No — skill-only | Yes |
| `POCKETHIVE_CONFIG_UPDATE` | No — skill-only (session ticket) | Yes |

This means:
- An AI doing **inspection** ("what swarms are running?", "show me queue depths") can
  call read-only tools directly without going through a skill.
- An AI doing **execution** ("run a load test", "scaffold a bundle") must go through
  the `pockethive.run-load-scenario` skill, which enforces the full
  scaffold → validate → deploy → create → start → observe → evidence → teardown cycle.
- The skill is the only path to mutating tools. There is no direct AI access to
  `swarm.create`, `bundle.scaffold`, `scenario.deploy`, or `debug.config-update`
  outside the skill context.
