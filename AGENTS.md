# AGENTS.md — PocketHive (Slim, Reference‑First)

**Scope & intent**  
This file is a **navigation and guardrails** page for both human and AI contributors. It avoids repeating details already covered elsewhere and points you to the **authoritative docs**. Follow these rules here; read the linked docs for specifics.

---

## 1) Rules **NON-NEGOTIABLE (NO DISCUSSION):**
  - The rules in this section are hard constraints, not suggestions.
  - Agents must not reinterpret, weaken, or silently bypass these rules.
  - Any exception requires explicit human approval for that specific task.

- **NFF (No Fraking Fallbacks).**
  - Do not add cascading defaults, heuristic fallback chains, or silent compatibility shims.
  - Every target must declare explicit adapter and explicit settings.
  - Do not auto-switch from one adapter/protocol to another.
  - Prefer explicit failure and explicit configuration over auto-recovery logic.
  - Rationale: fallback-heavy code quickly becomes hard to reason about and hard to trust.
- **No implicit backward compatibility.**
  - Breaking changes are acceptable unless compatibility is explicitly required.
- **No implicit Optional for core state/config flags.**
  - Use explicit required fields and explicit enums/states.
- **SSOT for contracts.**
  - One canonical schema/DTO per API/event/config contract.
  - Do not keep duplicate definitions, DTOs, schemas, validators, parsers, or mappers for the same contract format (API/event/config).
- **KISS.**
  - Prefer straightforward, maintainable implementations over clever abstractions.
- **No magic strings for core behavior.**
  - Never hardcode raw string literals to drive domain behavior (roles, protocols, event types, routing keys, header names).
  - Use shared constants/enums/contract types.
  - String parsing is allowed only at system boundaries, then normalize/map once.
- **Git safety (agents):**
  - **No pushes:** agents must not run `git push` (ever).
  - **No commits by default:** agents must not create commits unless a human makes an **EXPLICIT REQUEST TO COMMIT**.
- **Tests must use only official ingress/API paths.**
  - Do not point tests, E2E checks, or test diagnostics at direct service ports as a substitute for the supported entrypoint.
  - Use the official public path/interface for the environment under test (for example the UI ingress / documented API base), not backend container ports.
  - Direct service ports may be used only when the test explicitly exists to verify that specific service interface itself, with explicit human approval.

---

### 1.1 Java & libraries
- **Java 21 (LTS)** across services.
- **Lombok is allowed** to keep code concise. Recommended annotations:
  - `@Value`, `@Builder`, `@With` for immutable DTOs/configs
  - `@Data` (careful with equals/hashCode), `@RequiredArgsConstructor`
  - `@Slf4j` for logging
- Do **not** expose Lombok types in public JSON contracts (use plain records/DTOs for on‑wire types as defined in the contracts).

### 1.2 Boundaries (SOLID in PocketHive terms)
- **SRP:** each module does one thing (orchestrator, swarm‑controller, generator, moderator, processor, postprocessor, trigger). Don’t blend concerns.
- **OCP:** extend via new components/handlers. Do **not** modify shared contracts for “just this case.”
- **LSP:** adapter implementations must preserve behavior (e.g., a new sink still honors idempotency & timeouts).
- **ISP:** keep interfaces small—`Publisher`, `Consumer`, `Shaper`, `MetricsSink`.
- **DIP:** domain logic depends on ports; concrete adapters (RabbitMQ/HTTP/DB) live in infra. See `ARCHITECTURE.md` for layering.

### 1.3 Messaging & routing
- **Do not hand‑craft routing keys.** Use the shared routing utility and the examples in `ARCHITECTURE.md` / `ORCHESTRATOR-REST.md`.
- When adding a new signal or event, **update the contract + topics file** first; code follows the contract.

### 1.4 Observability (baseline)
- Propagate **correlationId** and **(if used) trace context** end‑to‑end as specified in `correlation-vs-idempotency.md`.

### 1.5 Tests & CI
- Follow the **control‑plane testing** strategy in `docs/ci/control-plane-testing.md` (Testcontainers, RabbitMQ, WireMock, etc.).
- For PocketHive stack verification, treat direct container/service ports as implementation detail, not test target, unless explicitly approved.
- For usage/dev commands, **do not copy here**—use `docs/USAGE.md`.

---

## 2) Authoritative sources (details and implementation guidance)

- **System architecture & contracts**
  - Architecture (single source of truth): `docs/ARCHITECTURE.md`
  - Orchestrator API surface: `docs/ORCHESTRATOR-REST.md`
  - Control‑plane worker notes: `docs/control-plane/worker-guide.md`
  - Correlation vs idempotency (must‑read): `docs/correlation-vs-idempotency.md`

- **Ops**
  - CI/control‑plane testing strategy: `docs/ci/control-plane-testing.md`

- **How to use & run**
  - Usage & local run: `docs/USAGE.md`
  - Project overview hub: `docs/README.md`

- **Agent hygiene**
  - AI guidelines: `docs/ai/AI_GUIDELINES.md`
  - HiveMind workflow: `docs/ai/HIVEMIND_WORKFLOW.md`
  - Review checklist (PR gate): `docs/ai/REVIEW_CHECKLIST.md`
  - Task template for agent work: `docs/ai/TASK_TEMPLATE.md`
  - UI/React guidelines: `docs/ai/UI_REACT_GUIDELINES.md`
  - Java guidelines: `docs/ai/JAVA_GUIDELINES.md`

> Precedence: if any referenced doc conflicts with §1 Rules, **§1 Rules win**.
> On conflict or ambiguity, stop and ask for explicit human decision.

---

## 3) Contribution workflow (short)

1. **Plan**: If your change affects routing, message schema, or REST, edit the relevant doc first (see §2) and get review.
2. **Implement**: Keep code inside its module’s boundaries; prefer ports + adapters; use Lombok to avoid boilerplate.
3. **Test**: Add/adjust tests at the right layer per `control-plane-testing.md`.
4. **Observe**: Ensure logs/metrics match `observability.md`.
5. **Review**: Run through `ai/REVIEW_CHECKLIST.md` before opening a PR.

**Protected areas — require explicit approval:**
- Routing utility, shared message envelopes, public contracts, prod compose/k8s manifests, and security config.

---

## 4) Quick glossary (PocketHive‑specific)

- **Signal / Event names, routing keys** — canonical list lives in the **Architecture** doc (see §2).  
- **Correlation vs Idempotency** — how we wait for results, deduplicate, and time out (see §2).  
- **Ports/Adapters** — small interfaces vs transport‑specific implementations (see `ARCHITECTURE.md`).

---

## 5) When in doubt

- Don’t guess. **Link to the source doc** in your PR and align code to it.
- If you need a new behavior, propose it by editing the right doc (contract/topics/REST) and ping reviewers.

---

## 6) Local orchestration tools (for AIs)

- PocketHive agent work should use the globally configured HiveMind project memory when available. Use the workflow in `docs/ai/HIVEMIND_WORKFLOW.md` with `project_id=pockethive`; do not create repo-local HiveMind storage or start a local HiveMind API as an implicit fallback.
- `build-hive.sh` in the repo root is the **canonical entrypoint** for local PocketHive rebuild/redeploy cycles: it rebuilds worker/service artifacts as needed and restarts the local `docker-compose` stack. Prefer using it over ad‑hoc `docker`/`mvn` commands when you want a full local refresh.
- For production-like HiveForge swarm deploys, use the `docs/HIVEFORGE.md` Agent MCP Deploy Checklist. Deploy through HiveForge MCP only; do not inspect Proxmox/hosts or run direct Docker/SSH commands as a deployment workaround.
- `tools/pockethive-mcp/` is the **canonical PocketHive MCP server** for agents and IDEs:
  - Use it for scenario authoring, swarm lifecycle, workflow evidence, environment status, runtime debug, worker logs/version, topology drift, manifest validation, and governed runtime cleanup.
  - Start stdio with `npm run mcp:start`.
  - Start Streamable HTTP with `npm run mcp:start:http`; clients connect to `http://localhost:3100/mcp`.
  - Runtime cleanup tools are exposed here as `runtime_cleanup_plan` and `runtime_cleanup_execute` by default. Register `runtime_cleanup_execute` behind HiveGate for governed approval and execution.
  - Worker/runtime diagnostics are exposed here as `runtime_tail_worker_logs`, `runtime_get_worker_version`, `runtime_list_workers`, `runtime_inspect_worker`, `runtime_diff_swarm_runtime`, `runtime_control_plane_status`, `runtime_rabbit_topology_snapshot`, `runtime_swarm_timeline`, and `runtime_manifest_validate` by default.
  - Dotted conceptual names like `runtime.cleanup.plan` require `PH_MCP_TOOL_NAME_MODE=legacy` or `both`; default agent configs should use underscore tool names.
- `tools/mcp-orchestrator-debug/` is lower-level debug tooling for Orchestrator / Scenario Manager / RabbitMQ:
  - `client.mjs` talks directly to the Orchestrator REST API, Scenario Manager API, and control‑plane via AMQP (no MCP needed).
  - `server.mjs` is legacy/additive debug MCP tooling. Do not configure it as the product PocketHive MCP surface for normal agent work.
  - Typical usage from repo root:
    - `POCKETHIVE_AUTH_USERNAME=local-admin node tools/mcp-orchestrator-debug/client.mjs list-swarms`
    - `POCKETHIVE_AUTH_USERNAME=local-admin node tools/mcp-orchestrator-debug/client.mjs get-swarm <swarmId>`
    - `POCKETHIVE_AUTH_USERNAME=local-admin node tools/mcp-orchestrator-debug/client.mjs swarm-snapshot <swarmId>`
    - `POCKETHIVE_AUTH_USERNAME=local-admin node tools/mcp-orchestrator-debug/client.mjs worker-configs <swarmId>`
    - `POCKETHIVE_AUTH_USERNAME=local-admin node tools/mcp-orchestrator-debug/client.mjs reload-scenarios`
  - Use the CLI for emergency/local diagnostics such as **running swarms, worker configs, queues, control‑plane traffic, and scenario reloads** instead of hand‑crafting `curl`/`rabbitmqctl` calls.
