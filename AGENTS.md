# AGENTS.md — PocketHive (Slim, Reference‑First)

**Scope & intent**  
This file is a **navigation and guardrails** page for both human and AI contributors. It avoids repeating details already covered elsewhere and points you to the **authoritative docs**. Follow these rules here; read the linked docs for specifics.

---

## 1) Authoritative sources (read these first)

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
  - Review checklist (PR gate): `docs/ai/REVIEW_CHECKLIST.md`
  - Task template for agent work: `docs/ai/TASK_TEMPLATE.md`

> If anything here conflicts with those files, **the linked docs win**.

---

## 2) Minimal, enforceable rules (no duplication)

- **No cascading defaults, no implicit back-compat:** When configuring services or libraries, never layer fallback property chains. Assume breaking changes are acceptable unless the task explicitly calls for backward compatibility.
- **No implicit Optionals:** Do not use `Optional` for core state like enablement/configuration flags unless a rule explicitly allows it. Every case where Optional is to be used must be consulted with humans.
- **KISS:** Bias toward the simplest viable solution. Avoid clever or convoluted code paths when a straightforward approach works; complexity must be justified explicitly.

### 2.1 Java & libraries
- **Java 21 (LTS)** across services.
- **Lombok is allowed** to keep code concise. Recommended annotations:
  - `@Value`, `@Builder`, `@With` for immutable DTOs/configs
  - `@Data` (careful with equals/hashCode), `@RequiredArgsConstructor`
  - `@Slf4j` for logging
- Do **not** expose Lombok types in public JSON contracts (use plain records/DTOs for on‑wire types as defined in the contracts).

### 2.2 Boundaries (SOLID in PocketHive terms)
- **SRP:** each module does one thing (orchestrator, swarm‑controller, generator, moderator, processor, postprocessor, trigger). Don’t blend concerns.
- **OCP:** extend via new components/handlers. Do **not** modify shared contracts for “just this case.”
- **LSP:** adapter implementations must preserve behavior (e.g., a new sink still honors idempotency & timeouts).
- **ISP:** keep interfaces small—`Publisher`, `Consumer`, `Shaper`, `MetricsSink`.
- **DIP:** domain logic depends on ports; concrete adapters (RabbitMQ/HTTP/DB) live in infra. See `ARCHITECTURE.md` for layering.

### 2.3 Messaging & routing
- **Do not hand‑craft routing keys.** Use the shared routing utility and the examples in `ARCHITECTURE.md` / `ORCHESTRATOR-REST.md`.
- When adding a new signal or event, **update the contract + topics file** first; code follows the contract.

### 2.4 Observability (baseline)
- Propagate **correlationId** and **(if used) trace context** end‑to‑end as specified in `correlation-vs-idempotency.md`.

### 2.5 Tests & CI
- Follow the **control‑plane testing** strategy in `docs/ci/control-plane-testing.md` (Testcontainers, RabbitMQ, WireMock, etc.).
- For usage/dev commands, **do not copy here**—use `docs/USAGE.md`.

---

## 3) Contribution workflow (short)

1. **Plan**: If your change affects routing, message schema, or REST, edit the relevant doc first (see §1) and get review.
2. **Implement**: Keep code inside its module’s boundaries; prefer ports + adapters; use Lombok to avoid boilerplate.
3. **Test**: Add/adjust tests at the right layer per `control-plane-testing.md`.
4. **Observe**: Ensure logs/metrics match `observability.md`.
5. **Review**: Run through `ai/REVIEW_CHECKLIST.md` before opening a PR.

**Protected areas — require explicit approval:**
- Routing utility, shared message envelopes, public contracts, prod compose/k8s manifests, and security config.

---

## 4) Quick glossary (PocketHive‑specific)

- **Signal / Event names, routing keys** — canonical list lives in the **Architecture** doc (see §1).  
- **Correlation vs Idempotency** — how we wait for results, deduplicate, and time out (see §1).  
- **Ports/Adapters** — small interfaces vs transport‑specific implementations (see `ARCHITECTURE.md`).

---

## 5) When in doubt

- Don’t guess. **Link to the source doc** in your PR and align code to it.
- If you need a new behavior, propose it by editing the right doc (contract/topics/REST) and ping reviewers.
