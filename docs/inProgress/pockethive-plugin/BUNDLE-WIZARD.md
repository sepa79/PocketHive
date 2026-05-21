# Bundle Creation Wizard — Risk-Based Agentic Flow

## Status
`IN PROGRESS`

## Problem

Creating a PocketHive scenario bundle requires decisions across 6+ domains
(pipeline shape, data sourcing, request templates, SUT binding, traffic
shaping, observability). A linear wizard over-collects, a free-form chat
under-validates. Both fail in AI chat because:

- Context window drift — by step 6 the agent forgets step 1 constraints
- Over-collection — asking for buffer guard config when the user wants a smoke test
- Hallucination — the agent invents config fields that don't exist
- No backtracking — "actually I need TCP not HTTP" at step 5 means starting over

## Solution: Risk-Ordered Progressive Discovery

The wizard is not a fixed sequence. It is a **decision tree** where the
agent follows a risk-ordered checklist, skipping branches that don't apply.
The MCP session tools enforce structural correctness; the wizard spec
defines the *conversation strategy*.

### Risk ordering principle

Decisions are ordered by **cost of getting them wrong**:

1. **Structural** (HIGH) — pipeline shape, protocol, worker roles.
   Wrong here = throw everything away.
2. **Behavioural** (HIGH) — what the system under test expects.
   Wrong here = every request fails.
3. **Data** (MEDIUM) — where payloads come from, what they look like.
   Wrong here = garbage in, garbage out.
4. **Shaping** (MEDIUM) — rates, concurrency, traffic profiles.
   Wrong here = overload or underload, but fixable at runtime.
5. **Observability** (LOW) — metrics mode, debug capture, clearing export.
   Wrong here = missing data, but no runtime failure.
6. **Polish** (LOW) — variables, descriptions, SUT profiles.
   Wrong here = inconvenience only.

The agent must resolve each tier before moving to the next. Within a tier,
the agent can ask multiple questions or infer from context.

## Decision tree

```
START
  │
  ├─ 1. INTENT (structural — what kind of test?)
  │    Q: "What are you testing? REST API / SOAP / TCP / message replay?"
  │    Q: "Do you have an existing Postman collection, OpenAPI spec, or sample requests?"
  │    Infers: protocol (HTTP|TCP), pipeline pattern, whether request-builder is needed
  │    MCP: wizard.start; wizard.complete internally creates the scenario and pipeline
  │    Gate: scenario scaffold exists with correct bee roles before proceeding
  │
  ├─ 2. TARGET (behavioural — what are you hitting?)
  │    Q: "What's the target URL / host:port?"
  │    Q: "Does it need auth? TLS?"
  │    Infers: baseUrl, SSL config, auth headers
  │    MCP: bee.config.set (processor.baseUrl), sut.bind
  │    Gate: processor can reach the target (or WireMock/TCP Mock placeholder is set)
  │    Skip if: user said "I'll configure the SUT later"
  │
  ├─ 3. REQUEST SHAPE (behavioural — what do requests look like?)
  │    Q: "Show me a sample request (or point me to a Postman collection / OpenAPI spec)"
  │    Q: "How many different call types? (single endpoint vs multi-call)"
  │    Infers: templates needed, serviceId/callId pairs, content-type
  │    MCP: template.http.add, template.http.attach
  │    Gate: at least one template validates through the MCP validation tool
  │    Skip if: protocol=TCP with static body (template is inline)
  │
  ├─ 4. DATA SOURCE (data — where do payloads come from?)
  │    Q: "Generate synthetic data, or replay from a dataset?"
  │    If synthetic:
  │      Q: "What fields vary per request? (I'll build a Pebble template)"
  │      MCP: bee.config.set (generator, inputs.type=SCHEDULER)
  │    If dataset:
  │      Q: "CSV file? Redis list? Multiple lists?"
  │      MCP: bee.config.set (generator, inputs.type=REDIS_DATASET)
  │    Gate: generator config is complete and template renders without error
  │    Skip if: user provides a complete generator body inline
  │
  ├─ 5. TRAFFIC SHAPE (shaping — how hard, how long?)
  │    Q: "Constant rate, ramp, or sine wave?"
  │    Q: "Target rate? Duration? (or just 'a quick smoke test')"
  │    Infers: moderator mode, scheduler rate, maxMessages, plan stages
  │    MCP: bee.config.set (moderator), plan.stage.add, traffic-policy.set
  │    Defaults: 10 msg/s constant, no moderator, no plan stages
  │    Gate: none — defaults are safe
  │    Skip if: user said "just a quick test" → use defaults
  │
  ├─ 5b. VARIABLES (data — parameterised profiles?)
  │    Q: "Do you need different config per environment or region?"
  │    Infers: whether variables.yaml is needed, profile names, variable definitions
  │    MCP: variables.set (creates/updates variables.yaml in the bundle)
  │    Defaults: no variables.yaml — skip entirely
  │    Gate: none — optional
  │    Skip if: user doesn't mention parameterisation
  │
  ├─ 6. OBSERVABILITY (observability — what do you want to see?)
  │    Q: "Need detailed per-transaction metrics? Clearing file export?"
  │    Infers: postprocessor publish-all-metrics, clearing-export bee
  │    MCP: bee.config.set (postprocessor), optionally bee.add (clearing-export)
  │    Defaults: postprocessor enabled, publish-all-metrics=false
  │    Gate: none — defaults are safe
  │    Skip if: user doesn't mention it → use defaults
  │
  └─ 7. FINALIZE
       MCP: session.validate
       Show: validation result, diff preview, action log
       Q: "Ready to generate the bundle? Anything to change?"
       MCP: bundle.generate → bundle.export
       MCP: session.feedback
```

## Adaptive skip logic

The agent does not ask questions it can answer from context:

| Signal | Skips |
|---|---|
| User provides Postman collection | Steps 1, 3 (infer protocol, templates) |
| User provides OpenAPI spec | Steps 1, 3 |
| User says "REST smoke test against localhost:8080" | Steps 1, 2, 5 |
| User says "replay this CSV at 100/s" | Steps 1, 4 |
| User says "same as bundle-X but with TCP" | Steps 3, 4, 5, 6 (clone + modify) |
| Bundle already exists (modify mode) | Steps 1–4 may be pre-filled |

The agent should state what it inferred and give the user a chance to
correct before proceeding:

```
Agent: "I'll set this up as an HTTP REST pipeline with a request-builder,
        hitting http://localhost:8080. Generator will use the scheduler
        at 10 msg/s. Sound right, or do you want to change anything?"
User:  "Make it 50/s and add a moderator with sine wave"
Agent: [adjusts steps 5 only, doesn't re-ask steps 1-4]
```

## Conversation heuristics

### H1 — Summarise-before-commit

Before calling any MCP tool that creates structure (`scenario.create`,
`pipeline.create.*`, `bee.add`), the agent summarises what it will do
and waits for confirmation. This prevents the most expensive mistakes.

```
Agent: "I'm going to create a bundle called 'payment-load' with:
        - Generator (scheduler, 50 msg/s)
        - Request Builder (HTTP templates)
        - Processor (HTTP, POST to /api/payments)
        - Postprocessor
        Shall I go ahead?"
```

### H2 — Validate-after-each-tier

After completing a risk tier, the agent runs `session.validate` and
reports any issues before moving on. This catches structural problems
early when they're cheap to fix.

### H3 — Offer defaults, don't demand answers

For LOW-risk tiers (5, 6), the agent states what defaults it will use
and only asks if the user wants to change them:

```
Agent: "I'll use these defaults — change anything?
        - Rate: 10 msg/s constant (no moderator)
        - Metrics: standard (not detailed)
        - No clearing export"
```

### H4 — One question per message for HIGH-risk tiers

For tiers 1–3, the agent asks one focused question at a time. For tiers
4–6, it can batch questions or use defaults.

### H5 — Show the artifact, not just describe it

After generating templates or config, the agent shows the actual YAML
snippet (read-only, from the session working copy) so the user can
verify it matches their intent.

### H6 — Backtracking is explicit and safe

If the user says "actually make it TCP", the agent:
1. States what will change: "This will replace the processor HTTP config with TCP config and remove the request-builder."
2. Calls the appropriate MCP tools (`bee.remove`, `bee.config.set`)
3. Re-validates from the affected tier downward
4. Does NOT re-ask questions from unaffected tiers

## MCP tool mapping per tier

The public novice-facing intake surface is `wizard.start`,
`wizard.answer`, `wizard.summary`, and `wizard.complete`.

Lower-level authoring tools use dot-delimited naming to match the existing MCP
server convention (`swarm.list`, `bundle.validate`, etc.). Those lower-level
tools may be called internally by `wizard.complete` or by advanced agents, but
they are not the primary novice interface.

| Tier | MCP tools used |
|---|---|
| 1 Intent | Public: `wizard.start`; internal/advanced: `scenario.create`, `pipeline.create.rest` / `pipeline.create.tcp` / `pipeline.create.sequence` |
| 2 Target | `bee.config.set` (processor), `sut.bind` |
| 3 Request | `template.http.add`, `template.http.attach`, `import.postman` |
| 4 Data | `bee.config.set` (generator inputs) |
| 5 Shaping | `bee.config.set` (moderator), `plan.stage.add`, `traffic-policy.set` |
| 5b Variables | `variables.set` |
| 6 Observability | `bee.config.set` (postprocessor), `bee.add` (clearing-export) |
| 7 Finalize | `session.validate`, `session.preview-diff`, `bundle.generate`, `bundle.export`, `session.feedback` |

### Public vs Advanced State Model

The wizard has one user-visible state model and one lower-level authoring
model.

| Layer | Tools | Who calls it | Writes bundle files? |
|---|---|---|---|
| Novice wizard | `wizard.start`, `wizard.answer`, `wizard.summary`, `wizard.complete` | AI assistant on behalf of a novice user | Only `wizard.complete` |
| Authoring session | `session.validate`, `session.preview-diff`, `session.feedback` | Advanced agents or wizard internals | No direct file writes except declared session artifacts |
| Domain edits | `scenario.*`, `pipeline.*`, `bee.*`, `template.*`, `variables.*`, `sut.*`, `traffic-policy.*`, `plan.*` | Advanced agents or wizard internals | Guarded bundle/session writes |
| Export/docs | `bundle.generate`, `bundle.export`, `bundle.docs.*` | Advanced agents or wizard internals | Guarded bundle writes |

For novice creation, the assistant should not expose the lower-level tool chain
as the primary interface. It should collect intent through `wizard.*`, then let
`wizard.complete` orchestrate validation and generation.

### Tool naming convention

The concept spec (`pockethive-scenario-builder-mcp-plugin-spec.md`) used
underscore names (`start_session`, `create_scenario`). Do not use underscore
tool names in implementation.

For novice creation, use `wizard.*`. For lower-level advanced operations, use
dot-delimited names such as `scenario.create`, `pipeline.create.rest`,
`bee.config.set`, and `bundle.export`.

## Pipeline patterns (tier 1 resolution)

The agent resolves the user's intent to one of these patterns. Each pattern
has a corresponding `pipeline.create.*` MCP tool:

| Pattern | Tool | Bees | When to use |
|---|---|---|---|
| `rest-simple` | `pipeline.create.rest` | generator → processor → postprocessor | Single REST endpoint, inline body |
| `rest-rbuilder` | `pipeline.create.rest` (+rbuilder) | generator → request-builder → processor → postprocessor | Multiple REST endpoints or SOAP, templates on disk |
| `rest-moderated` | `pipeline.create.rest` (+moderator) | generator → moderator → processor → postprocessor | Need runtime rate shaping |
| `rest-full` | `pipeline.create.rest` (+rbuilder +moderator) | generator → moderator → request-builder → processor → postprocessor | Full pipeline with templates + shaping |
| `sequence` | `pipeline.create.sequence` | generator → http-sequence → postprocessor | Multi-step HTTP journeys (login → action → logout) |
| `tcp-simple` | `pipeline.create.tcp` | generator → processor(TCP) → postprocessor | TCP protocol, single message type |
| `tcp-rbuilder` | `pipeline.create.tcp` (+rbuilder) | generator → request-builder → processor(TCP) → postprocessor | TCP with disk-backed templates |
| `tcp-moderated` | `pipeline.create.tcp` (+moderator) | generator → moderator → processor(TCP) → postprocessor | TCP with runtime rate shaping |
| `dataset-replay` | `pipeline.create.rest` (+dataset) | generator(REDIS_DATASET) → processor → postprocessor | Replay recorded data from Redis |
| `clearing` | `pipeline.create.rest` (+clearing) | generator → processor → clearing-export | Generate clearing/settlement files |

The `pipeline.create.*` tools accept optional flags:
- `useRequestBuilder` (boolean) — adds request-builder bee
- `includeModerator` (boolean) — adds moderator bee
- `inputType` (`SCHEDULER` | `REDIS_DATASET`) — sets generator input
- `terminalRole` (`postprocessor` | `clearing-export`) — sets terminal bee

The agent picks the closest match and confirms with the user. If the user
describes something that doesn't fit, the agent asks a clarifying question
rather than guessing.

## Example conversations

### Complete novice transcript

```
User:  "I need to test our onboarding API, but I don't know how to make a bundle."

Agent: [calls wizard.start]
       "No problem. First, what kind of target are we testing: REST, TCP,
        SOAP, or a message replay?"

User:  "REST. It is a POST to /api/onboarding."

Agent: [calls wizard.answer { questionId: "protocol", answer: "REST" }]
       "What base URL should the processor call? If you are not ready to hit
        the real service, I can bind this to WireMock instead."

User:  "Use WireMock for now."

Agent: [calls wizard.answer { questionId: "target", answer: "wiremock" }]
       "What should a typical request body look like?"

User:  "JSON with customerId, email, and plan."

Agent: [calls wizard.answer { questionId: "requestShape", answer: "customerId/email/plan JSON" }]
       "I'll create a REST smoke-test bundle:
        - Generator -> Processor -> Postprocessor
        - POST /api/onboarding
        - WireMock as the SUT double
        - synthetic JSON payloads with customerId, email, and plan
        - 10 messages per second
        - standard metrics and journal/tap evidence
        Shall I generate it?"

User:  "Yes."

Agent: [calls wizard.summary, then wizard.complete]
       "Bundle generated and validated.
        Created:
        - scenario.yaml
        - mock-config/wiremock/
        - README.md
        - FLOW_DOCUMENT.md
        - CHANGELOG.md

        Validation passed. Evidence still needs a runtime run:
        deploy the scenario, start a swarm, then collect journal, queues,
        tap sample, mock requests, and Prometheus metrics."
```

### Quick smoke test

```
User:  "Create a bundle that hits POST /api/health on localhost:8080"
Agent: "I'll create a simple REST bundle:
        - Generator → Processor → Postprocessor
        - POST /api/health on http://localhost:8080
        - 10 msg/s, empty JSON body
        - Bundle name: api-health-smoke
        Go ahead?"
User:  "Yes"
Agent: [calls: wizard.start, wizard.answer, wizard.summary, wizard.complete;
        wizard internally creates the REST pipeline and validates it]
       "Bundle created and validated. No issues found.
        [shows scenario.yaml snippet]
        Want me to deploy it, or change anything?"
```

### From Postman collection

```
User:  "I have a Postman collection for our payments API, create a load test"
Agent: "Share the collection file and I'll import it. What's the target
        base URL for the API?"
User:  [provides file] "https://payments-staging.example.com"
Agent: "I found 6 requests in the collection across 2 folders.
        I'll set this up as:
        - Generator → Request Builder → Processor → Postprocessor
        - 6 HTTP templates (one per Postman request)
        - serviceId: payments, callIds: AuthorizePayment, CapturePayment, ...
        - Target: https://payments-staging.example.com
        - Default rate: 10 msg/s
        Sound right?"
User:  "Yes but make it 100/s with a 30s ramp"
Agent: [calls: wizard.start, wizard.answer, wizard.summary, wizard.complete;
        wizard internally imports Postman, binds the SUT, adds the request-builder,
        adds traffic shaping, and validates]
       "Bundle created. Validation found 1 warning:
        ⚠ Template 'RefundPayment' uses {{auth_token}} but no variable defined.
        Want me to add a variable for that, or will you set it in the SUT config?"
```

### Modify existing

```
User:  "Add a sine wave moderator to bundle payment-load"
Agent: "I'll load payment-load and add a moderator between generator and processor.
        What rate range and period?
        (e.g. 10–100 msg/s with 60s period)"
User:  "20 to 200, 120 second period"
Agent: [advanced path calls: scenario.load,
        bee.add(moderator), bee.connect(generator→moderator→processor),
        bee.config.set(moderator, mode.type=sine, sine.minRatePerSec=20,
          sine.maxRatePerSec=200, sine.periodSeconds=120),
        session.validate]
       "Done. Moderator added with sine wave 20–200 msg/s, 120s period.
        [shows diff: +moderator bee, +topology edge, +queue suffix]
        Validation passed. Export the updated bundle?"
```

## Error recovery patterns

| Error | Agent behaviour |
|---|---|
| Validation fails after tier 1 | Show error, ask user to clarify intent, rebuild scaffold |
| Template render fails (tier 3) | Show the Pebble/SpEL error, offer to fix the expression |
| Unknown config field | Refuse to set it. "That field doesn't exist in the processor capability manifest. Did you mean X?" |
| User asks for unsupported protocol | "PocketHive supports HTTP, TCP, and ISO8583. Which of these fits your use case?" |
| Session already has a moderator, user asks to add one | "There's already a moderator. Want me to update its config instead?" |
| Conflicting instructions | State the conflict explicitly: "You asked for 100 msg/s but also maxMessages=50. That would finish in 0.5s. Which do you want to adjust?" |

## Guardrails (from AGENT-RULES.md, reinforced here)

- **Never invent config fields.** Only use fields from capability manifests.
- **Never write YAML directly.** All changes go through MCP session tools.
- **Never skip validation.** Run `session.validate` before `bundle.generate`.
- **Never assume protocol.** Always confirm HTTP vs TCP vs ISO8583.
- **Never set secrets in config.** Auth tokens go in SUT environment or variables.
- **Always show the diff** before exporting. User must approve.
- **Never use shell tools.** The wizard cannot rebuild services, run Git,
  inspect Docker, read container logs, or execute local commands.
- **Use PocketHive evidence.** Verify with journals, taps, queues, metrics,
  mock request history, datasets, and PocketHive-provided log APIs if they
  exist. Loki is future-only unless exposed through PocketHive.

## Scenario contract coverage

The wizard must account for the full `Scenario.java` model. The canonical
scenario YAML fields (from `Scenario.java` + bundle layout) are:

| Field | Java type | Wizard tier | Notes |
|---|---|---|---|
| `id` | String | 1 (Intent) | Bundle id = scenario id |
| `name` | String | 1 (Intent) | Human-friendly name |
| `description` | String | 7 (Finalize) | Optional, agent can auto-generate |
| `template` | SwarmTemplate | 1 (Intent) | Contains `image` + `bees[]` |
| `template.bees[]` | List<Bee> | 1 (Intent) | Created by `pipeline.create.*` |
| `topology` | Topology | 1 (Intent) | Auto-generated by `pipeline.create.*` from bee ports |
| `trafficPolicy` | TrafficPolicy | 5 (Shaping) | Buffer guard config, optional |
| `plan` | Map<String,Object> | 5 (Shaping) | Opaque execution plan (ramp/steady stages), optional |
| `variables.yaml` | (bundle file) | 5b (Variables) | Definitions, profiles, values — see SCENARIO_VARIABLES.md |
| `sut/` | (bundle directory) | 2 (Target) | Bundle-local SUT definitions |
| `templates/` | (bundle directory) | 3 (Request) | HTTP/TCP request templates |

The `plan` field is an opaque `Map<String,Object>` at the Scenario Manager
level — the Orchestrator interprets its structure. The wizard's
`plan.stage.add` tool writes stages into this map.

The `variables.yaml` file is a separate bundle artifact (not a Scenario
field). It defines variable definitions, named profiles, and a
`(profile × SUT)` value matrix. The wizard's `variables.set` tool
creates/updates this file. See `docs/scenarios/SCENARIO_VARIABLES.md`
for the full contract.

## Two paths to bundle creation

There are two complementary paths:

| Path | Tool | When | Phase |
|---|---|---|---|
| **IDE quick-pick** | `bundle.scaffold` | User knows exactly what they want, clicks [+ New] in Scenarios tab | Phase 1 |
| **AI chat wizard** | `wizard.start` → `wizard.answer` → `wizard.summary` → `wizard.complete` | User describes intent in natural language, agent guides them | Phase 4 |

`bundle.scaffold` is the **fast path** — a single MCP tool that writes a
minimal scaffold directly. It remains useful even after Phase 4 lands
because it's instant and doesn't require a conversation. The wizard is
the **primary path** for AI-assisted creation where the agent can ask
clarifying questions, infer intent, and validate incrementally.

## Integration with existing specs

This wizard spec defines the **conversation strategy**. It depends on:

| Spec | What it provides |
|---|---|
| `pockethive-scenario-builder-mcp-plugin-spec.md` | MCP session tools, safety constraints, feedback report contract |
| `SCENARIO_CONTRACT.md` | Canonical bundle structure, bee fields, topology, SUT |
| `SCENARIO_VARIABLES.md` | Variables definitions, profiles, value matrix |
| `Scenario.java` | Authoritative field list: id, name, description, template, topology, trafficPolicy, plan |
| `capabilities/*.latest.yaml` | Valid config fields per worker role |
| `IMPLEMENTATION-GUIDE.md` §10 | `bundle.scaffold` MCP tool for IDE quick-pick |
| `MCP-APPS.md` | Phase 1.5 evidence widget plus future MCP Apps UI ideas |

## What this spec does NOT cover

- The MCP tool implementations (see `pockethive-scenario-builder-mcp-plugin-spec.md`)
- The VS Code / IntelliJ UI for the wizard (see Phase 4 in `README.md`)
- Runtime swarm management (see `VIEWS.md`, Hive tab)
- Bundle validation internals (see the validator implementation behind
  `bundle.validate`; it must be called in-process or through a PocketHive API)
