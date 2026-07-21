# Managed Test Data Assurance and Qualification Strategy

Status: **design readiness PASS (green)** — Grade 0;
`G-DESIGN-READY-v1` passed for an internal decision workshop and
`G-TEAM-APPROVED-v1` is `NOT_EVALUATED`. Implementation, runtime, capacity,
accessibility and release evidence is `NOT_RUN` and no product qualification is
claimed.

Applies to: `managed-test-data-lifecycle-generic-spec.md` and
`managed-datasets-operator-ui-design-spec.md`

Team decision brief: `managed-test-data-team-review-brief.md`

Last updated: 2026-07-20

## 1. Purpose and authority

This is the risk-based assurance companion to the Managed Test Data
Architecture and Lifecycle Specification. It explains how PocketHive will gain
defensible confidence that the feature is useful, safe, durable, observable,
and does not distort a performance test.

The architecture specification remains authoritative for scope and normative
acceptance criteria:

- section 27 defines the core feature MVP, the separate mandatory sensitive
  profile gate, and deferred claims;
- section 28 defines the numbered release/claim gates;
- the operator UI design companion defines the normative `DSUI` child
  requirements for `FUN-014` and every wireframe-supported view/state;
- this document defines the risk model, oracles, charters, checks, debrief, and
  confidence standard used to satisfy those gates.

Neither document says that the current implementation already has the
capability. A design review, a test-case count, a green dashboard, or a case
study is not evidence of PocketHive behaviour.

### 1.1 Current assessment and evidence boundary

“Green” is meaningful only when the named gate and evidence stage are shown
together. The current baseline has the following honest status:

| Evidence stage | Named gate | Status | Meaning |
|---|---|---|---|
| Design coherence | `G-DESIGN-READY-v1` | **PASS — green** | The architecture, lifecycle, assurance, UI requirements, wireframe behaviour and 43-ID UI registry form a reviewable, internally consistent proposal. The control/work/data boundaries and non-claims are explicit. |
| Cross-functional approval | `G-TEAM-APPROVED-v1` | `NOT_EVALUATED` | Product, Architecture, Security, Operations, UX and QA have not yet recorded approval of the target architecture, constraints, ownership and non-claims. |
| Implementation entry | `G-IMPLEMENTATION-READY-v1` | `NOT_EVALUATED` | Canonical API/event/common schemas, executable port boundaries, model/TCK plan and the first source/SUT profile still require approval and implementation planning. |
| Internal system evidence | `G-M1-INTERNAL-v1` | `NOT_RUN` | No running vertical slice has yet demonstrated start/readiness/supply ordering, durable commit, recovery or independent-oracle behaviour. |
| Core release qualification | `dataset-qualified-core` | `NOT_RUN` | The 50,000-record, two-swarm, live-resize, 24-hour, security, accessibility and non-interference claims remain qualification gates. |
| Sensitive/deferred profiles | `Q-SENSITIVE-ENTERPRISE-v1` and optional M4 gates | `NOT_CLAIMED` | Sensitive, exact interrupted lifecycle recovery, HA/restore, exclusive consumption and wider scale are outside the current green design decision. |

The machine-readable UI registry records the same three evidence stages for
every `DSUI` child: design review may be `PASS` while implementation and release
remain `NOT_RUN`. A missing runtime artifact can never be represented by the
design result.

## 2. Rapid Software Testing posture

The strategy uses a Rapid Software Testing (RST) posture: testing is a skilled,
context-driven investigation that combines learning, modelling, test design,
execution, and interpretation. Automation supplies fast repeatable checks and
observations; it does not replace investigation or decide whether an unexplained
result is acceptable.

The team shall tell three connected stories for every release decision:

1. **Product story**: what the build does under representative and hostile
   conditions.
2. **Testing story**: how we know, which parts were observed, and where the
   oracles may be wrong.
3. **Quality story**: why the observed behaviour is acceptable for the named
   users, data classification, deployment, and workload claim.

Testing is risk-first. We spend the most effort where failure could invalidate
the traffic, disclose data, lose durable truth, duplicate an external effect,
or create a false proof. We continually update charters as the product model,
risks, and evidence change.

## 3. Product model: zoom out, then inspect the corners

Use SFDIPOT to avoid testing only the happy-path API:

| Dimension | Managed-Dataset model and questions |
|---|---|
| **Structure** | Scenario Manager definitions, the Orchestrator-owned Managed Dataset bounded module, PostgreSQL, RabbitMQ, Swarm Controllers, common contracts/SDKs, workers/local projections, authorised Orchestrator read models, production `ui-v2`, MCP, SUT/provider, volumes, clocks, keys. Which state belongs to the feature, which belongs to the wider platform, and what is reconstructed? |
| **Functions** | Define, bind, evaluate fitness, provision, deduplicate, commit, publish, hydrate, select, refresh, quiesce, revoke, purge, prove, reconcile, recover, authorise/filter/aggregate status, inspect and diagnose. What happens before, during, and after each transition? |
| **Data** | Schemas, Fitness Contracts/evaluations, classifications, versions, partitions/pools, records, generations, idempotency intent, claims/fences, snapshots/deltas, receipts, ciphertext, retention, evidence. Which representation can leak or become inconsistent? |
| **Interfaces** | Official Orchestrator ingress and internal Dataset ports, canonical UI read DTOs, browser routes/cache/storage, JDBC, Rabbit protocol, common contracts, worker/manager SDKs, provider/SUT calls, MCP JSON, Docker/config/secret files, logs/metrics. Where can identity, scope, ordering, type, freshness, or redaction be lost? |
| **Platform** | The Orchestrator container and shared JVM/heap/GC/thread pools, PostgreSQL/Rabbit pools, host CPU/RAM/disk/network, supported dependency versions, TLS/PKI, filesystem/volume and resource limits. Which shared resource or privilege can contaminate traffic or widen the trust boundary? |
| **Operations** | Install, migrate, start, configure, rotate, scale, diagnose, back up, restore, upgrade, roll back, purge, revoke, incident response. Can an operator distinguish safe-idle, degraded, reconciling, and failed? |
| **Time** | UTC/clock offset, validity, refresh budget, retry, lease, fence, credential/certificate/key rotation, offline horizon, retention, restart RTO, 24-hour run. What happens exactly at every boundary? |

Quality criteria to consider on every charter are capability, reliability,
integrity, performance, scalability, security/privacy, compatibility,
recoverability, operability, observability, supportability, and testability.
The brief values valid continuous traffic over feature breadth; exact-order,
single-use, and transaction-bound semantics are rejected in MVP rather than
simulated badly.

## 4. Risk model and priorities

Priority is a judgement based on impact, exposure, detectability, reversibility,
and uncertainty, not a multiplication formula. Review it after every material
finding or architecture change.

- **P0**: could invalidate the test, disclose prohibited/sensitive data, lose an
  acknowledged durable result, or cause an uncontrolled external effect. It
  blocks release and cannot be accepted through a schedule waiver.
- **P1**: could materially disrupt continuous operation, create a misleading
  support claim, break existing PocketHive behaviour, or make recovery unsafe.
  It needs explicit evidence and risk-owner disposition.
- **P2**: bounded operability, diagnosability, or efficiency issue with a safe
  workaround. It remains visible and time-bounded.

### 4.1 Loss themes and triage lanes

The detailed register is not a queue of interchangeable P0 rows. Each risk has
one **primary loss theme** below, even when it can contribute to other losses.
The release decision is made first at loss-theme level, then at detailed-risk
level. A theme remains red if any applicable P0 is uncontrolled, any required
oracle is absent or not independent, or contradictory evidence is unresolved.
Counting passing risks or averaging confidence across a theme is prohibited.

| Theme | Primary loss being prevented | Detailed risks in the primary lane | First control question |
|---|---|---|---|
| `LT-01 UNSAFE_MATERIAL` | Expired, invalid, semantically wrong, wrongly selected or wrongly materialised data reaches the SUT and invalidates traffic | `R-EXPIRY`, `R-PROJECTION`, `R-ACTIVATION`, `R-AUTH-PATH`, `R-ROTATION-RACE`, `R-TIME-ROLLBACK`, `R-DATA-QUALITY`, `R-FITNESS`, `R-SNAPSHOT-INTEGRITY`, `R-STATIC-DRIFT`, `R-SELECTION-BIAS`, `R-CANONICAL-MISMATCH`, `R-VALIDITY-STARVATION` | Can an independent sink and semantic oracle prove that every selected generation was eligible for the exact binding, contract and time? |
| `LT-02 SCOPE_OR_SECRET_LOSS` | A value, credential, identity or existence fact escapes its authorised classification or scope | `R-LEAK`, `R-SCOPE`, `R-AUTHZ-ORACLE`, `R-IDENTITY-LIFECYCLE`, `R-TCB-BLAST`, `R-EGRESS`, `R-SENSITIVE-ADMISSION`, `R-KEY-LOSS`, `R-UI-SCOPE-LEAK` | Does an independently implemented policy oracle agree, and do canary plus zero-effect ledgers show no disclosure or effect on every denied ingress? |
| `LT-03 FALSE_READINESS_OR_PROOF` | UI, API, MCP, evidence or release wording promotes missing, stale or contradictory observations into readiness or proof | `R-FALSE-PROOF`, `R-CLAIM-INFLATION`, `R-EVIDENCE-INTEGRITY`, `R-UI-FALSE-STATE`, `R-UI-STALE`, `R-UI-EXCLUSION`, `R-SSOT-DRIFT` | Can each displayed or exported claim be traced to one canonical contract, every applicable child assertion and an independent oracle without inference or fallback? |
| `LT-04 UNCONTROLLED_EXTERNAL_EFFECT` | Retry, stale ownership, topology error, ambiguous cancellation, unsafe live resize or incomplete lifecycle control creates a duplicate, orphaned, unbounded or wrongly scoped provider/SUT effect | `R-SUPPLY-ROUTE`, `R-SUPPLY-TOPOLOGY`, `R-CONTROL-PLANE-BYPASS`, `R-LIVE-RESIZE`, `R-TRAFFIC-REPLAY`, `R-CANCEL-AMBIGUITY`, `R-EXTERNAL-LIFECYCLE`, `R-CAPACITY` | Does the provider/SUT append-only ledger reconcile one immutable intent to the allowed effects, cancellation disposition, limits, fences and cleanup disposition? |
| `LT-05 DURABLE_TRUTH_OR_RECOVERY_LOSS` | Acknowledged truth is lost, stale/deleted state is resurrected, or recovery proceeds from the wrong owner/version | `R-DURABLE`, `R-DELETION-RESURRECTION`, `R-RESTORE-RESURRECTION`, `R-RUNTIME-OWNER`, `R-CONTROLLER`, `R-REDIS-REGRESSION`, `R-REVISION-CHURN`, `R-LIVENESS-RECOVERY`, `R-UPGRADE` | Do restart/upgrade/restore observations reconcile PostgreSQL authority, fences, outbox, projections and external effects without importing a wider platform claim? |
| `LT-06 TEST_DISTORTION_OR_STARVATION` | Dataset, proof or UI work steals shared capacity, biases selection, hides offered load or prevents representative continuous operation | `R-MEASURE`, `R-REFRESH`, `R-COLD-START`, `R-SHARED-INFRA`, `R-EVIDENCE-LOAD`, `R-CONTROL-DOS`, `R-UI-LOAD`, `R-OPERABILITY` | Do open-loop paired runs and shared-resource telemetry show the frozen workload, control probes and operators remain inside their independent limits? |

Within a lane, investigate the risk with the greatest credible loss, current
exposure, uncertainty and weakest oracle first. A secondary-theme relationship
is recorded in the session/risk evidence when discovered; it does not duplicate
the risk or dilute its primary owner.

### 4.2 Detailed risk register

| Risk | Priority | Failure/loss to expose | Acceptance links | Minimum confidence |
|---|---|---|---|---|
| `R-EXPIRY` | P0 | Expired, revoked, clock-unsafe, or invalidated material reaches the SUT | `LIF-002`--`LIF-003` | Grade 4, then Grade 5 in soak |
| `R-LEAK` | P0 | A sensitive value escapes through messaging, logs, metrics, debug, MCP or its untrusted client, Orchestrator/container metadata or artifacts, plaintext storage, or backup; embedding combines Dataset material with the Orchestrator's existing privileges | `SEC-001`--`SEC-008`, `SEC-016` | Grade 4; rotation/endurance at Grade 5 |
| `R-MEASURE` | P0 | The in-process Dataset lifecycle steals Orchestrator JVM CPU, heap, GC, threads, executors, JDBC/Rabbit connections or host resources, or introduces a request-thread remote call, corrupting traffic or control-plane results | `PER-001`--`PER-005`, `PER-008`, `PER-010` | Grade 5 |
| `R-DURABLE` | P0 | A Dataset caller receives success that is lost, a retry duplicates durable accounting/external effect, a multi-step relationship is corrupted, a stale owner commits, or embedded-module recovery is confused with recovery of an interrupted wider swarm command | `FUN-002`, `FUN-009`, `LIF-005`--`LIF-006`, `LIF-013`, `DUR-001`--`DUR-005` | Grade 4; recovery soak at Grade 5 |
| `R-FALSE-PROOF` | P0 | MCP promotes a publish/count to end-to-end truth, agrees with itself while reality differs, or hides a missing child assertion behind a parent pass | `EVD-001`--`EVD-004`, `EVD-009` | Grade 4 |
| `R-SCOPE` | P0 | A worker, producer, operator, or untrusted MCP client crosses environment/Dataset/partition/pool/swarm/projection scope | `FUN-001`, `SEC-003`, `SEC-008` | Grade 4 |
| `R-SUPPLY-ROUTE` | P0 | The Dataset module accepts a handcrafted, inferred, forged, expired or superseded supply route, or dispatches after membership/incarnation change, so work reaches the wrong producer, scope or topology and creates an uncontrolled external effect | `FUN-012`, `DUR-003`, `OPS-006` | Grade 4 |
| `R-SUPPLY-TOPOLOGY` | P0 | The controller-selected canonical WorkItem route has undeclared or unqualified durability, bounds, confirm, redelivery, acknowledgement, delivery-limit or dead-letter semantics; topology drift is silently recreated/downgraded, Rabbit loops on mismatch, or shared traffic resources are exhausted | `FUN-012`, `DUR-003`, `PER-008`, `OPS-002`, `OPS-006` | Grade 4 on the exact controller-owned route/profile; Grade 5 at named capacity/endurance |
| `R-CONTROL-PLANE-BYPASS` | P0 | A swarm template/plan/start/stop/remove/config/status event bypasses canonical `ph.control`; supply is sent before the exact producer has a fresh successful start outcome plus `RUNNING`/input-ready/topology-fence observation; or Dataset supply enters a control queue, creating dual command ownership, lost correlation, work addressed to a stopped/stale producer or control-plane starvation | `FUN-012`, `PER-008`, `OPS-006`; `DUR-009`/`OPS-009` only for the wider interrupted-command recovery claim | Grade 4 two-stage lane/schema/order/freshness/route evidence for MVP; Grade 5 only for `Q-PLATFORM-RECOVERY-v1` |
| `R-LIVE-RESIZE` | P0 | Raising the target above both the prior target and current effective supply opens no fill cycle; a same/lower target is misclassified as an increase; lowering deletes/deprovisions data or invalidates an active safe view; a stale policy wins; or an accepted command is presented as convergence | `FUN-006`, `LIF-001`, `LIF-009`, `LIF-013`, `PER-005` | Grade 4 deterministic transition/fault evidence; Grade 5 in the 24-hour profile |
| `R-PROJECTION` | P0 | A mixed/stale/wrong-scope snapshot or final slot mapping sends the wrong record, generation, or context to the SUT | `FUN-004`--`FUN-005`, `LIF-003` | Grade 4 |
| `R-ACTIVATION` | P0 | A selector/auth revision activates before every exact final processor can resolve it, or a forged/mutated WorkItem payload, reference, prepared model, stage chain, or destination is accepted | `FUN-004`--`FUN-006`, `SEC-014`, `EVD-001` | Grade 4; stress at Grade 5 |
| `R-AUTH-PATH` | P0 | A source result, SUT credential, derived authentication value, stale auth revision, or private signing key enters a generic WorkItem/wrong service/persistent artifact | `FUN-005`, `SEC-004`--`SEC-007` | Grade 4; artifact/endurance at Grade 5 |
| `R-ROTATION-RACE` | P0 | A request passes its last check, pauses, and writes after the provider invalidates the old generation/credential | `LIF-002`--`LIF-003` | Grade 4 with deterministic race control |
| `R-TIME-ROLLBACK` | P0 | A restart, reference-clock loss, host rollback, leap/step, suspend, or stale time sample makes expired material appear usable again | `LIF-002`--`LIF-003`, `DUR-001`, `DUR-011`, `OPS-003` | Grade 4; restart/soak at Grade 5 |
| `R-TRAFFIC-REPLAY` | P0 | Rabbit redelivery, worker restart, or operator replay produces an unrecognised duplicate external transaction; an integrity HMAC is mistaken for uniqueness or exactly-once delivery | `FUN-005`, `FUN-011`, `DUR-002`--`DUR-003`, `EVD-006` | Grade 4; endurance at Grade 5 |
| `R-DATA-QUALITY` | P0 | Incorrect mapping, coercion, default, relationship, uniqueness, classification, or batch atomicity creates plausible but wrong records and misleading traffic | `FUN-001`--`FUN-003`, `FUN-009`--`FUN-010`, `SEC-001` | Grade 4 with independent mapper oracle |
| `R-RESULT-ROUTING` | P0 | HTTP/TCP transport success is mistaken for business completion; a wrong, missing, pending or ambiguous state reaches the completed Dataset; a failed result fills primary supply; or response-controlled/dynamic routing crosses an authorised Dataset target | `FUN-002`, `FUN-003`, `FUN-015`, `LIF-004`--`LIF-005`, `SEC-003` | Grade 4 with independent protocol/result classifier and Dataset-target oracle |
| `R-FITNESS` | P0 | A count-only, stale, wrong-version or weakly evidenced decision marks a Dataset ready although it is unfit for the binding's declared environment and use | `FUN-010`, `FUN-013`, `LIF-001`--`LIF-004`, `OPS-003` | Grade 4; continuity at Grade 5 in `Q-MVP-1K-24H` |
| `R-SNAPSHOT-INTEGRITY` | P0 | A truncated, duplicated, mixed, forged, rolled-back, or semantically incomplete snapshot passes transport checks and becomes selectable | `FUN-004`, `DUR-002`, `DUR-010`, `EVD-001` | Grade 4 |
| `R-AUTHZ-ORACLE` | P0 | Product and test use the same defective policy implementation, so a cross-scope allow or required deny appears correct | `FUN-001`, `SEC-003`, `SEC-008` | Grade 4 with independently implemented policy oracle |
| `R-DELETION-RESURRECTION` | P0 | An MVP tombstone loses to a stale delta, retained local projection, worker rejoin, key-retained row, or external reconciliation and the deleted record becomes selectable again | `LIF-012`, `DUR-010`, `OPS-004` | Grade 4 |
| `R-RESTORE-RESURRECTION` | P0 | An admitted `Q-HA-RESTORE-v1` backup or point-in-time restore predating the newest deletion epoch resurrects a deleted record or external effect | `DUR-008`, `DUR-012`, `SEC-009`--`SEC-010` | Grade 5 on the isolated restore profile |
| `R-EXTERNAL-LIFECYCLE` | P0 | Continuous provisioning accumulates orphan accounts/entities, exceeds provider or legal limits, cannot be decommissioned, or deletes an entity still in use | `LIF-001`, `LIF-005`, `LIF-012`, `OPS-004` | Grade 4; 24-hour accumulation at Grade 5 |
| `R-CANCEL-AMBIGUITY` | P0 | An accepted local cancellation intent is mistaken for provider cancellation, reservation is released before conclusive no-effect evidence, a late/ambiguous effect is discarded, or pause/timeout/shutdown synthesises cancellation and permits duplicate or stranded external work | `LIF-005`, `LIF-014`, `DUR-003` | Grade 4 with deterministic race model and independent provider ledger |
| `R-STATIC-DRIFT` | P0 | A reusable external record is closed, blocked, reclassified, or otherwise changed outside PocketHive while remaining locally selectable | `LIF-002`--`LIF-004`, `LIF-011`, `OPS-003` | Grade 4; refresh/endurance at Grade 5 |
| `R-SELECTION-BIAS` | P0 | Selection hotspots, skew, replacement, or correlated deterministic seeds make traffic unrepresentative or overload a subset of the SUT | `FUN-008`, `FUN-011`, `PER-001`--`PER-004`, `EVD-006` | Grade 4; named load claim at Grade 5 |
| `R-IDENTITY-LIFECYCLE` | P0 | CA bootstrap, SAN policy, certificate/token renewal, revocation, partial rollout, bearer-only sensitive admission, or stolen-token replay leaves an unauthenticated or over-privileged path | `SEC-003`, `SEC-005`, `SEC-008`, `SEC-012` | Grade 4 |
| `R-TCB-BLAST` | P0 | Compromise of the shared Orchestrator process/container (including its Docker-control authority), a final processor, host, debug surface, or retained local projection exposes more scopes, time, or material than the declared trust boundary | `SEC-002`, `SEC-005`--`SEC-007`, `SEC-015` | Grade 4; sensitive endurance at Grade 5 |
| `R-CANONICAL-MISMATCH` | P0 | Stages authenticate different interpretations of duplicate headers, query tuples, Unicode, binary bodies or protocol frames, so a valid attestation can protect unintended request semantics | `FUN-005`, `SEC-014` | Grade 4 with independent cross-language vectors |
| `R-EGRESS` | P0 | A hostile binding/provider value causes SSRF, unsafe redirect/DNS target, parser/resource exhaustion, or context injection | `FUN-005`, `SEC-011` | Grade 4 |
| `R-RUNTIME-OWNER` | P0 platform risk | Orchestrator's durable desired-runtime ownership for a dynamic Swarm Controller is missing, duplicated or stale, so controller/host restart loses the swarm runtime, starts competing controller incarnations, dispatches before exact plan/topology reconciliation, or duplicates an external effect | `DUR-004`--`DUR-005`, `DUR-009`, `OPS-007`, `OPS-009` | Blocks only an exact interrupted swarm-lifecycle recovery claim; remains an explicit platform limitation for the core Dataset MVP |
| `R-SENSITIVE-ADMISSION` | P0 | A core-only build/profile admits, persists, provisions, hydrates or sends `SENSITIVE_TEST`, or UI/MCP presents a core pass as enterprise-sensitive qualification | applicable MVP rows plus `SEC-002`, `SEC-004`--`SEC-005`, `SEC-007`, `SEC-012`--`SEC-015`, `EVD-008`--`EVD-009`, section 27.4 | Grade 4 plus every grade required by `Q-SENSITIVE-ENTERPRISE-v1`; deferred restore claims are not part of this gate |
| `R-CONTROLLER` | P1 platform risk | Scenario Manager/Orchestrator restart loses an interrupted plan, binding, command or desired-runtime intent, or accepts platform mutation before authoritative reconciliation | `DUR-004`--`DUR-005`, `DUR-009`, `OPS-009` | Required for a wider swarm-lifecycle recovery claim; recorded as a non-claim for the core Dataset MVP |
| `R-REFRESH` | P1 | Provider throttling, ambiguity, credentials, or an aligned refresh wave depletes safe material during 24/7 use | `LIF-002`, `LIF-004`--`LIF-005`, `PER-001` | Grade 5 |
| `R-COLD-START` | P1 | Worker/fleet hydration overloads the shared Orchestrator JVM, Dataset module, PostgreSQL or network, or starves control work and already-running swarms | `PER-005`, optional `PER-006`--`PER-007` | Grade 4 for MVP; Grade 5 for 30-swarm claim |
| `R-CAPACITY` | P1 | The configured 50,000-record target is mistaken for qualified support, or supply reservations, conflicting inventory owners, live resize, late successes or retained projections exceed record/byte/storage/provider limits and retention cannot recover space | `FUN-008`, `LIF-001`, `LIF-013`, `PER-001`, `PER-005`, `PER-008`, `OPS-004` | Grade 4 for bounds; Grade 5 for the named 50,000-record/endurance claim |
| `R-SHARED-INFRA` | P1 | Embedded Dataset work or Rabbit/PostgreSQL flow, pool, lock or I/O contention damages existing PocketHive control/data planes in the same JVM and deployment | `PER-003`, `PER-008`, `PER-010` | Grade 5 |
| `R-REDIS-REGRESSION` | P1 | Additive feature changes Redis datasets, sequences, auth, debug, or state-loop semantics | `DUR-006`, `EVD-005` | Grade 4 |
| `R-KEY-LOSS` | P1 | Rotation/restore or a divergent or unverifiable key manifest loses the KEK/DEK required for live rows/backups, activates the wrong key state, reuses a nonce or falls back insecurely | `SEC-004`--`SEC-006`, `SEC-010`, `SEC-013`, `SEC-016` | Grade 4; restore claim at Grade 5 |
| `R-CLAIM-INFLATION` | P1 | A core, two-swarm/shared/reusable result is advertised as enterprise-sensitive, 30-swarm, isolated, consumable, HA, restricted-data compliant, or universal capacity | `FUN-008`, `PER-006`--`PER-007`, section 27.4, section 28.8 | Grade 5 per distinct performance/HA claim and the exact sensitive gate for a sensitive claim |
| `R-EVIDENCE-LOAD` | P1 | Per-transaction proof, scanning, or MCP polling becomes the bottleneck or drops evidence silently | `EVD-006`, `PER-001`, `PER-008` | Grade 5 |
| `R-VALIDITY-STARVATION` | P1 | The Dataset module reports sufficient inventory using a weaker minimum while a stricter shared consumer receives no eligible records | `LIF-001`--`LIF-004`, `PER-009`, `OPS-003` | Grade 4; source profile at Grade 5 |
| `R-REVISION-CHURN` | P1 | Rapid supersession accumulates candidates/deltas, prevents activation, exhausts memory, or lets one slow worker hold every consumer back indefinitely | `FUN-004`, `PER-005`, `PER-008` | Grade 4; stress at Grade 5 |
| `R-CONTROL-DOS` | P1 | Excessive definitions, hydration, MCP status reads, proof creation, refreshes, or hostile retries exhaust the shared Orchestrator JVM, Dataset executors, PostgreSQL/Rabbit pools, auth, or controller bulkheads | `PER-008`, `PER-010`, `EVD-006`, `OPS-004` | Grade 4; named load at Grade 5 |
| `R-LIVENESS-RECOVERY` | P1 | The shared Orchestrator container remains running but unhealthy, embedded-module recovery ownership is ambiguous, or crash-loop remediation creates duplicate or unsafe Dataset work | `DUR-004`--`DUR-005`, `OPS-002`--`OPS-005`, `OPS-007`; add `OPS-009` only for platform recovery | Grade 4 for feature scope |
| `R-UPGRADE` | P1 | Schema/image/protocol/key-policy change or mixed-version rollout corrupts state, weakens policy, breaks rollback, or creates an untestable downgrade | `DUR-002`, `DUR-004`, `OPS-001`--`OPS-002`, `OPS-008` | Grade 4; restore/HA claim at Grade 5 |
| `R-EVIDENCE-INTEGRITY` | P1 | A qualification record or exported evidence pack is altered, backdated, replayed, selectively regenerated, omits a required child assertion, or is signed by an identity unrelated to the exact qualified build/profile | `EVD-003`--`EVD-005`, `EVD-008`--`EVD-010` | Grade 4 for core activation and exported enterprise claims |
| `R-SSOT-DRIFT` | P0 | Lifecycle, UI, REST, event, scenario requirement/binding, proof, generated language types, wireframe copy or acceptance registry defines a different enum, field, status, revision namespace or semantic rule, so individually plausible components disagree or one bundle becomes coupled to another | `FUN-001`, `FUN-014`, `EVD-007`, `EVD-009`, `DSUI-API-001`, M0 | Zero unresolved deterministic drift at the design-ready and M0 gates; Grade 4 mutation evidence for release |
| `R-UI-FALSE-STATE` | P0 | The operator UI fabricates, defaults, joins or mislabels a count, Fitness result, horizon, revision, decision or proof fact, causing a user to believe an unsafe/unproven Dataset or swarm is ready | `FUN-014`, `DSUI-DATA-001`--`004`, `DSUI-SEM-001`--`002`, `DSUI-VIEW-001`--`008`, `DSUI-STATE-001`--`002` | Grade 4 |
| `R-UI-SCOPE-LEAK` | P0 | Server/client filtering, totals, facets, consumer links, cache reuse or proof queries reveal a hidden Dataset/swarm scope or sensitive value | `FUN-014`, `DSUI-API-002`--`003`, `DSUI-SEC-001`--`005` | Grade 4 with independent auth oracle and browser canaries |
| `R-UI-STALE` | P1 | A stale, partial, reconciling or unavailable response continues to appear current/green, extends `safeUntil`, or loses durable truth across restart | `FUN-014`, `DSUI-STATE-001`--`002`, `DSUI-PERF-006` | Grade 4; endurance at Grade 5 |
| `R-UI-LOAD` | P1 | Inventory joins, proof generation, polling or many browser sessions exhaust the shared Orchestrator read/JDBC/heap bulkhead and distort traffic/control results | `FUN-014`, `DSUI-ARCH-002`, `DSUI-PERF-001`--`006`, `PER-008`, `PER-010` | Grade 5 |
| `R-UI-EXCLUSION` | P2 | Width, zoom, keyboard, focus, colour, timing or screen-reader behavior hides an operational reason or prevents diagnosis | `FUN-014`, `DSUI-A11Y-001`--`002` | Grade 3 plus exploratory accessibility review |
| `R-OPERABILITY` | P2 | Status cannot distinguish idle, warming, degraded, starved, uncertain, and reconciling or cannot identify the safe action | `OPS-001`--`OPS-009`, sections 18, 22, 30 | Grade 3 plus exploratory review |

The risk owner records evidence gained, new uncertainty, residual exposure, and
the next charter after each test cycle. Closed means the risk is controlled for
a named profile, not impossible in every deployment.

#### 4.2.1 Cross-cutting readiness coverage

The following map prevents a locally green UI, broker check or happy-path flow
from standing in for the whole feature. “Design covered” means the obligation
has an explicit owner, acceptance link, oracle and future evidence gate; it is
not runtime evidence.

| Readiness concern | Acceptance/risk coverage | Accountable evidence owner | Required implementation/release evidence |
|---|---|---|---|
| Two-stage producer activation | `FUN-012`, `OPS-006`, `R-CONTROL-PLANE-BYPASS`, `R-SUPPLY-ROUTE` | Control-plane owner with Dataset application owner | `CH-30`: when stopped, canonical `swarm-start` on `ph.control`, correlated outcome and fresh exact-incarnation `RUNNING` plus input-ready route observation occur before one bounded `DATASET_SUPPLY`; timeout, stale status, stop/rebind and denied start publish no supply. Already-ready is an idempotent ensure-running decision, not a second lifecycle authority. |
| Rabbit lane and topology contract | `FUN-012`, `DUR-003`, `PER-008`, `OPS-006`, `R-SUPPLY-TOPOLOGY` | Swarm Controller/Rabbit platform owner | `OR-RABBIT` capture proves every lifecycle event remains on `ph.control`, supply uses only the controller-declared canonical WorkItem route, and the selected route's exact durability, bounds, confirm, ack/redelivery, delivery-limit and DLQ policy passes without adapter declaration or fallback. |
| 50,000-record support | `FUN-008`, `LIF-001`, `LIF-013`, `PER-001`--`PER-005`, `R-CAPACITY` | Performance QA lead | Grade 5 named profile at 50,000 eligible/55,000 maximum, representative maximum record bytes, two concurrent swarms, restart/backlog/endurance and measured headroom. Until then the wording is “designed for,” never “supports.” |
| Live target resize | `FUN-006`, `LIF-001`, `LIF-009`, `LIF-013`, `R-LIVE-RESIZE` | Dataset application owner | `CH-02` plus fault tests prove latest-generation coalescing, bounded incremental increase, no delete/deprovision on decrease, active-view safety, stale-fence rejection and separate accepted-versus-converged status. |
| State scenarios and operator decisions | `OPS-001`--`OPS-009`, `DSUI-STATE-001`--`002`, `R-OPERABILITY`, `R-UI-FALSE-STATE` | UX owner with Operations owner | `CH-35`/`CH-39` cover canonical swarm-runtime, producer-work and Dataset-availability axes independently, keep the finer durable supply-operation ledger separate, and exercise loading, reconciling, stale, limited, denied, empty, incompatible, failed, starved and uncertain presentations. |
| Durable scheduling, outbox and recovery | `DUR-001`--`DUR-005`, `OPS-002`, `OPS-005`, `R-DURABLE`, `R-LIVENESS-RECOVERY` | Dataset application owner | PostgreSQL due-index claims, short transactions, transactional outbox, wake-up plus repair sweep, restart reconciliation and no lock held across Rabbit/provider calls pass `CH-06`/`CH-07`. Scenario timeline and worker pacer counters are not reused as Dataset authority. |
| Reuse and add-back | `FUN-004`, `FUN-008`, `FUN-011`, `DUR-010`, `R-SNAPSHOT-INTEGRITY` | Dataset domain owner | MVP `SHARED` immutable local views prove concurrent non-destructive reuse and therefore require no add-back. Any future exclusive/consumable profile must qualify durable lease holder/token/epoch/expiry and stale-return rejection separately; Rabbit requeue is never the business return. |
| Fencing and idempotency | `FUN-009`, `FUN-012`, `DUR-002`--`DUR-003`, `R-DURABLE`, `R-TRAFFIC-REPLAY` | Dataset application owner with QA lead | Stable operation/reservation IDs, intent mismatch rejection, database uniqueness, route/claim epochs, stale-commit rejection, ambiguous external effects and publisher-confirm/consumer-ack boundaries pass independent database, Rabbit and provider-ledger checks. |
| Security, privacy and observability | `SEC-001`--`SEC-008`, `SEC-011`, `EVD-001`--`EVD-006`, `R-LEAK`, `R-SCOPE` | Security owner with Observability owner | Least-privilege identities per lane/API, official-ingress authorization, zero-effect denies, canary scans, redacted bounded telemetry, correlation versus idempotency separation and no values/secrets on `ph.control` or WorkItems pass adversarial checks. |
| UX, accessibility and responsiveness | `FUN-014`, all 43 `DSUI` children, `R-UI-EXCLUSION`, `R-UI-STALE`, `R-UI-LOAD` | UX owner with Accessibility QA owner | Registry-linked `CH-34`--`CH-40` evidence includes package authoring, ordered activation/supply/commit gates, orthogonal states, canonical response-to-DOM lineage, keyboard/focus, accessibility tree, screen reader, contrast/forced colours, 200% zoom, 320 CSS-pixel reflow, desktop/tablet/mobile, adverse states and qualified polling load. Screenshots alone cannot pass this row. |

At implementation kickoff each role is resolved to one named person in the
evidence manifest. An unresolved owner makes the corresponding gate
`INCONCLUSIVE`; shared ownership does not mean ownerless approval.

### 4.3 Applicability and MVP scope control

Hardening does not silently promote deferred HA, 30-swarm, HSM, consumable,
exclusive, ordered, transaction-bound, multi-region, or distributed-database
capabilities into MVP. A machine-readable applicability matrix shall map every
admitted deployment, data, source, allocation, replay, identity and external-
lifecycle profile to its required criteria and evidence. `NOT_APPLICABLE` needs
a reviewed reason and proof that the path fails admission before persistence,
provisioning or send; it is not a waiver for an admitted path.

There are two non-interchangeable release gates:

- `dataset-qualified-core` is the feature MVP: the durable reusable Dataset
  vertical slice for `NON_SENSITIVE_SYNTHETIC`. It may be released only after
  every applicable core parent/child assertion and operational/performance gate
  passes. It has no permission to admit `SENSITIVE_TEST`.
- `Q-SENSITIVE-ENTERPRISE-v1` is a mandatory additional qualification on the
  exact build, images, deployment and manifest before `SENSITIVE_TEST` can be
  admitted, persisted, provisioned, hydrated or sent outside the controlled
  qualification fixture. Every applicable sensitive assertion is non-waivable,
  and a core result is never inherited as security evidence.

UI, API, status, MCP and reports shall expose the highest passed profile and
reject or clearly label every unsupported higher claim. “Feature MVP” means the
qualified core Dataset capability; it does not mean sensitive-data capable.

For this feature MVP, an agent is an **untrusted Dataset evidence client** of
exactly `dataset_status`, `dataset_source_operation_status`, and
`dataset_prove`. The first two are reads; `dataset_prove` creates only bounded,
retained evidence with dedicated permission and idempotency. No other Dataset
MCP tool is an MVP capability. MCP evidence
never grants authority, constitutes approval, or certifies its own truth.
Dataset values, secrets and credentials are never returned to that client.
Product-side authentication, authorization, Dataset scope and admission checks
apply no matter whether the request originated through a Dataset MCP tool, an
existing generic MCP/workflow/swarm route, or another official ingress.

The MVP deployment contains no separate Dataset application container. Managed
Dataset is an Orchestrator-owned bounded module in the existing Orchestrator
process, with an explicit per-registration Dataset storage adapter as its authority
and explicit ports to common
contracts, the worker/manager SDKs, RabbitMQ and Swarm Controller. Qualification
therefore separates two claims that must not be conflated:

- **Feature-scoped Dataset durability** is an MVP claim. Acknowledged Dataset
  truth, idempotency outcomes, schedules, claims/fences, activation state and
  outbox intent survive Orchestrator process/container restart and reconcile
  from PostgreSQL. Stale ownership is fenced, work resumes without duplicate
  logical effects, and the module does not report ready before its own durable
  state is safe.
- **Exact interrupted swarm-lifecycle recovery** is the deferred
  `Q-PLATFORM-RECOVERY-v1` claim.
  The MVP does not claim that an interrupted create, plan handoff, configure,
  start, stop or remove command, or a lost dynamic Swarm Controller, is
  reconstructed exactly unless the wider PocketHive durability criteria are
  separately fixed and qualified. `R-RUNTIME-OWNER`, `R-CONTROLLER`, `DUR-009`
  and `OPS-009` remain visible; they do not silently become core Dataset release
  blockers or passing claims.

During an Orchestrator outage, no new Dataset supply, refresh, hydration or
control operation is promised. Already-running workers may continue only from
an already-activated local projection while its conservative safe horizon is
valid. On restart the Dataset module enters its own `RECONCILING` state,
recovers PostgreSQL/outbox/claims/schedules, fences stale routes, and fails
closed where current swarm membership or binding cannot be re-established.
This can preserve Dataset truth while wider swarm availability remains reduced;
reports and UI must state that boundary plainly.

General agent governance is outside this feature brief. Write-capable or
autonomous Dataset MCP operations, agent-model/prompt/provider qualification,
persistent agent memory, multi-agent coordination, and agent-specific approval,
kill-switch or signed tool-manifest programmes are deferred. If introduced,
they require a separate cross-cutting PocketHive specification and
qualification; this strategy supplies no release claim for them. This deferral
does not waive one MVP integration obligation: an untrusted client using any
existing generic workflow/swarm/MCP route must be unable to bypass the same
product-side Dataset authorization and admission controls. A denied attempt
must cause zero durable Dataset mutation, zero dispatch and zero external
effect, as confirmed by the independent policy and durable/external-ledger
oracles under `R-SCOPE` and `R-AUTHZ-ORACLE`.

Every admitted source still declares its external-entity disposition
(`AUTO_EXPIRES`, `DEPROVISIONABLE`, or `OWNER_RETAINED`), cumulative limits,
drift/revalidation rule and ambiguity contract. Every admitted traffic profile
declares `AT_LEAST_ONCE` or a qualified `SUT_IDEMPOTENT` contract. Every
sensitive identity profile requires sender-constrained credentials: workload
and MCP-downstream Dataset API tokens prove their RFC 8705 mTLS binding,
while a
declared DPoP ingress also proves key possession, nonce and replay controls.
Bearer-only credentials fail sensitive profile admission. Qualification
exercises only declared modes, but safe rejection of undeclared modes is always
MVP scope.

Omnibus acceptance rows are not allowed to hide partial coverage. The
machine-readable evidence index shall decompose every multi-clause acceptance
ID into stable child assertion IDs, for example `FUN-005.a`, while preserving
the normative parent text. Each child records its profile and preconditions,
stimulus, expected result, product and independent oracle, immutable evidence
references, outcome and reason. A parent is `PASS` only when every applicable
child is `PASS`; any missing, failed or inconclusive child makes the parent fail
or remain inconclusive. `NOT_APPLICABLE` requires the same reviewed admission-
rejection evidence as the parent applicability decision. Passing a subset of
children never creates a narrower implicit product claim.
These rules are the assurance interpretation of `EVD-009`; the registry and
evidence pack, not prose or a percentage, decide completeness.

The checked-in
[UI acceptance registry](managed-test-data-ui-acceptance-registry.json),
validated by its
[JSON Schema](managed-test-data-ui-acceptance-registry.schema.json), is the single
machine-readable Stage-0 traceability source for all 43 `DSUI` child
requirements of `FUN-014`. It records each exact ID, primary and supporting
charters, principal risks, core applicability and minimum confidence. A linter
shall compare it with the UI acceptance table and fail on a missing, duplicate,
unknown or stale ID, unknown charter/risk, invalid parent, wildcard ID, or
weakened grade. Human-readable charter tables in this document are summaries;
the JSON registry decides exact `DSUI` traceability.

That design registry is not a qualification result or a substitute for the
full `EVD-009` evidence index. Before M0 passes, every lifecycle acceptance
parent must also have its versioned child-assertion decomposition and every
child must reference an executable check or charter. A run-specific evidence
index then binds those immutable assertion definitions to outcomes and artifact
digests; it never edits the checked-in requirement definition to make a run
pass.

### 4.4 Staged delivery and evidence milestones

Implementation may be staged to shorten feedback, but milestones are evidence
checkpoints and named gates, not permission to blur profile boundaries:

| Milestone | Exit evidence | Permitted claim |
|---|---|---|
| `M0 Contracts and blockers` | Closed schemas/reason codes, applicability/child-assertion maps, common/SDK compatibility TCKs, protocol model, supported Rabbit baseline, `SupplyRouteLease`, Dataset PostgreSQL/outbox/reconciliation contract, core key manifest and extraction-safe module boundary are reviewed | Design ready for implementation; no managed-Dataset behavioural claim and no wider platform recovery claim |
| `M1 Core vertical slice` | Official ingress reaches the embedded non-sensitive Dataset module, PostgreSQL/outbox, controller-issued route lease, snapshot/local projections, two-swarm traffic, refresh, Orchestrator restart, both MCP status reads, bounded proof creation and the canonical nine-fact proof through `FLOW_PROVEN` using deterministic doubles, and passes the bounded transaction/publish/apply/restart/fence/time/auth/external-effect fault set required by `G-M1-INTERNAL-v1` against independent ledgers | Internal `dataset-dev-synthetic` evidence only; no release, scale, sensitive or enterprise claim |
| `M2 Qualified core MVP` | `dataset-qualified-core` passes every applicable core parent/child assertion, including feature-scoped PostgreSQL restart recovery, shared-JVM non-interference, independent-oracle faults, `Q-MVP-1K-24H` at 50,000 eligible records/55,000 maximum with live target changes, `Q-KNEE`, Redis compatibility and operations | The exact Dockerised reusable non-sensitive Dataset module in the existing Orchestrator container may be released; `SENSITIVE_TEST` remains rejected and exact interrupted swarm-lifecycle recovery remains an explicit non-claim |
| `M3 Sensitive enterprise gate` | The exact M2 build/deployment removes or mediates raw Docker control (or qualifies an extracted topology) and additionally passes `Q-SENSITIVE-ENTERPRISE-v1`, including encryption and key rotation, PKI/mTLS sender constraint, least privilege, signed qualification records, shared Orchestrator/container TCB limits and privilege blast radius, egress, canary leakage and hostile input at their required grades | Only the exact qualified profile may admit and advertise `SENSITIVE_TEST`; a partial security pass, unmediated raw Docker control or an unreviewed combined trust boundary permits neither |
| `M4 Measured extensions` | Each `Q-PLATFORM-RECOVERY-v1`, HA/restore, 30-swarm, specialised-cryptography, restricted-data compliance or other deferred profile passes its own complete manifest and gates | Only the independently qualified optional claim |

No milestone waives a P0 control, changes either release bar, or
allows evidence from a narrower stage to be extrapolated. Promotion records the
exact build, images, schemas, model/TCK versions and immutable child-assertion
evidence; a material change invalidates affected evidence as described in
section 12.

At M2, the `EVD-009` registry marks only core MVP children applicable and links
the independent proof that every sensitive profile path is denied. At M3, all
`Q-SENSITIVE-ENTERPRISE-v1` children become mandatory on the same exact build/
deployment; none may be inherited, waived or hidden as a core `NOT_APPLICABLE`.

## 5. Oracle strategy

An oracle is a fallible source of information about a problem. Use several,
prefer independent observations, and investigate disagreement.

### 5.1 Consistency oracles (HICCUPPS)

| Oracle | Questions |
|---|---|
| History | Does this preserve existing PocketHive Redis, readiness, routing, status, and restart behaviour? |
| Image | Does behaviour match the product's enterprise/security posture and avoid misleading operators? |
| Comparable products | What do mature local-projection, outbox, fenced-work, synthetic-data, and key-management systems demonstrate, and where is PocketHive different? |
| Claims | Does the build satisfy the exact acceptance ID and named profile, not a nearby narrative claim? |
| User expectations | Can a tester trust that traffic uses safe prepared data continuously without distorting the measurement? |
| Product | Are adjacent paths, APIs, status fields, schemas, and recovery behaviours internally consistent? |
| Purpose | Does this solve reusable onboarding/source output for traffic swarms without becoming a generic data platform? |
| Statutes/standards | Do security, privacy, data-protection, cryptography, protocol, and dependency-version obligations hold? |

Production case studies in specification section 33 are Comparable-product
oracles. They corroborate a mechanism; they cannot pass a PocketHive criterion.

### 5.2 Independent evidence chain

| Claim | Product observation | Independent oracle required for qualification |
|---|---|---|
| Source effect happened once | source operation status/receipt | provider/SUT-double append-only ledger or provider idempotency/status record |
| Supply reached the exact current producer topology, after producer readiness | Dataset-module lifecycle decision, lease/outbox/publish status | Correlated `ph.control` start outcome and fresh exact-incarnation `RUNNING`/input-ready observation, Swarm Controller topology/lease ledger, schema-validated capture of the selected canonical WorkItem route and producer-incarnation receipt |
| Mapped record is semantically correct | Dataset-module receipt, schema result, material projection | separately implemented reference mapper plus provider/source record, relationship and classification ledger |
| Dataset is fit for its declared use | exact Fitness Contract reference/digest, evaluation ID/input-vector digest, state, `safeUntil`, bounded reasons and evidence references | independently implemented evaluator over the frozen contract, authoritative Dataset snapshot, source/provenance ledgers, trusted time and exact binding environment/use |
| Record is durable | Dataset-module receipt/proof | read-only PostgreSQL invariant query before and after Orchestrator process/container restart |
| Snapshot is complete and authentic | Dataset-module page/hash status and worker-ready acknowledgement | independently generated manifest over exact scope, revision, schema/projection version, ordered record identities, counts, bytes, page ranges and delta-chain continuity |
| Final materializer/activation/selector revisions applied | worker/controller acknowledgement/status | controlled snapshot server, durable activation ledger, bounded per-revision worker aggregates and deterministic sampled selections |
| Safe record reached SUT | `FLOW_PROVEN` receipt | external traffic sink matching opaque record/generation and boundary clock |
| One logical transaction had the declared external effect | traffic receipt and attestation chain | external sink/provider ledger keyed by immutable offered transaction ID and, where promised, the SUT idempotency key |
| Access decision is correct, including through an existing generic workflow/swarm/MCP route | Orchestrator/Dataset-module/auth/MCP allow or deny plus audit reason | independent declarative policy evaluator built from the written access matrix, plus durable Dataset, dispatch and external-effect ledgers proving that a deny caused zero effects; never product authorization code or generated bindings |
| Time decision is safe across restart | worker trusted-time/readiness status | independent authenticated reference clock, restart-event trace and persisted-floor observation |
| External entities are bounded and cleaned | Dataset-module inventory and decommission receipt | provider-side inventory/status ledger plus independent cumulative create/delete/reconcile counters |
| Selection represents the frozen policy | worker selection aggregates | reference sampler/statistical distribution test over immutable policy, seed and eligible population |
| No central hot-path call | worker instrumentation | Dataset-module/DB/Redis/provider request counters and network trace |
| No leakage | product redaction status | canary scanner over every enumerated sink/encoding and container/image metadata |
| Running-but-unhealthy service recovered safely | product health and operation status | container/supervisor events, durable operation identity and provider/DB ledgers |
| Dataset feature recovered after Orchestrator restart | Dataset-module reconciliation/operation status | PostgreSQL invariants, outbox/claim/schedule/fence ledgers and a safe post-restart transaction, separated from any interrupted swarm-command result |
| Dynamic Swarm Controller runtime recovered under its sole owner (only when `Q-PLATFORM-RECOVERY-v1` is claimed) | Orchestrator/controller desired/actual status | durable desired-runtime row, Docker labelled inventory and independently compared plan/config/worker/topology/lease state before dispatch |
| Sensitive admission is authorised by the passed gate | product classification/profile decision and reported highest profile | independent applicability/evidence-index evaluator plus DB/Rabbit/canary observations proving core-only rejection |
| No material regression | PocketHive metrics | open-loop generator, shared-resource telemetry, paired raw samples/confidence analysis |
| Recovery completed | component health | durable inventory/ledger convergence and successful safe transaction after fault; health alone cannot prove either Dataset reconciliation or wider swarm-command recovery |
| Exported enterprise evidence is authentic | proof digest and report metadata | detached signature, trusted timestamp, signer/build identity chain and independent verification over the complete immutable evidence index |

#### 5.2.1 Named independent-oracle catalogue

Every qualification manifest names the concrete implementation, owner, version,
configuration digest and independence argument for each applicable oracle. A
generic label such as “logs”, “database” or “test double” is not sufficient.

| Oracle ID | Required independent observation | Independence boundary and failure meaning |
|---|---|---|
| `OR-EXT-EFFECT` | Provider/SUT-double append-only ledger for immutable intent, attempt, idempotency key, provider entity/status and externally visible business effect | Written by the controlled provider/sink, not the Dataset receipt path. Missing or irreconcilable effects make durability, idempotency, cleanup and flow claims `INCONCLUSIVE` or `FAIL`, never assumed successful. |
| `OR-MAPPER` | Separately implemented reference mapper and data-quality evaluator over frozen source records, relationships, classifications and expected projections | Derived from the reviewed mapping matrix without importing product mapping code or generated bindings. Mutation qualification must show detection of wrong field, coercion, relationship, classification and batch-accounting defects. |
| `OR-FITNESS` | Separately implemented Fitness Contract evaluator over the exact contract digest, binding environment/use, authoritative snapshot, provenance and trusted time | It may share immutable input files but no product evaluator/library. Missing inputs or disagreement cannot be converted to `PASS`. |
| `OR-AUTHZ` | Declarative access-matrix evaluator plus enumeration and zero-effect observations for every official ingress | It uses neither product policy code nor generated policy bindings. A deny passes only when the independent decision agrees and Dataset, dispatch and external-effect ledgers remain unchanged. |
| `OR-PG` | Scoped read-only PostgreSQL invariant queries for committed records, idempotency outcomes, operations, claims/fences, outbox, schedules, activations, tombstones and accepted-time floors | Queries are owned/reviewed outside the persistence adapter and run only in the approved component-observer profile. Product API success alone cannot establish durable truth. |
| `OR-RABBIT` | Schema-validated topology/vhost/ACL/queue-argument/routing capture plus publish return, confirm, delivery and acknowledgement history, including proof that every swarm control event used canonical `ph.control` and no supply WorkItem entered it | Captured by the broker test harness independently of publisher status. A confirm does not prove producer apply, durable Dataset commit or SUT flow, and traffic on the wrong lane is a failure even if the receiver happened to act. |
| `OR-TIME` | Authenticated reference-clock samples, uncertainty, process/restart/suspend trace and direct observation of each persisted monotonic floor | Independent of the product wall clock and readiness calculation. Unavailable, stale or rolled-back observation makes an expiring safety claim unknown and must reduce eligibility. |
| `OR-WORKER-SINK` | Worker-local snapshot/materialization/activation and selection ledger joined to the external request/SUT sink by opaque record, generation, binding, revision and transaction IDs | Neither ledger is produced by the central proof composer. It distinguishes installed, activated, selected, attempted and externally accepted facts without promotion. |
| `OR-UI` | Canonical API-response-to-DOM field lineage, browser network/storage/cache capture, accessibility tree, keyboard/focus/reflow evidence and assistive-technology observations | Uses official ingress and inspects rendered output; screenshots alone are not the oracle. Any client-derived safety decision, hidden-scope aggregate, persisted sensitive state or inaccessible reason fails the corresponding `DSUI` assertion. |
| `OR-CANARY` | Unique exact, encoded and transformed canary corpus plus coverage manifest and scanner across API/DOM/cache, logs, errors, metrics/traces, Rabbit/DLQ, persistence/backups, reports, MCP, browser and container/image artifacts | Canary generation and scanning are independent of product redaction and serialization. An unscanned declared sink, scanner false negative or any canary occurrence blocks the no-leakage claim. |
| `OR-RESOURCE` | Whole-Orchestrator and Dataset-attributed CPU/allocation/heap/GC/thread/executor/JDBC/Rabbit/DB-lock/WAL/network telemetry, open-loop traffic measurements and control/authorization probes | Collected outside the business decision path and reconciled with offered load. Missing stalls, rejected work or shared-pool waits make non-interference evidence inconclusive. |
| `OR-REGISTRY` | Acceptance-registry schema/lint, canonical contract/TCK drift graph, run evidence-index closure and deliberately incomplete/contradictory pack tests | The checker consumes canonical artifacts without rewriting them. Any missing/duplicate/wildcard child, contract mismatch or unlinked applicable outcome blocks the parent and gate. |

The same code path must not generate both sides of an allegedly independent
comparison. MCP is useful evidence but never the deciding oracle for MCP truth.
E2E stimulus and product queries use official PocketHive ingress. Direct
PostgreSQL/Rabbit observers are used only in separately approved tests whose
explicit subject is that component interface; they never replace the official
path or become a customer-facing proof mechanism.

For data quality and authorization, independence means **separate semantics and
implementation**, not a second call to the same library. The reference mapper
and policy evaluator shall be derived from a reviewed mapping/access matrix,
owned or reviewed outside the product implementation, versioned with the test
manifest, and exercised with mutation tests. Qualification shall demonstrate
that each oracle detects seeded wrong-field, wrong-relationship,
wrong-classification, cross-scope-allow, and required-deny defects. Agreement
between two implementations is evidence only when their shared inputs and
assumptions are explicit; disagreement makes the result inconclusive until
resolved.

Product-generated digests establish reproducibility, not truth. An exported
enterprise evidence pack additionally needs a detached signature and trusted
timestamp over its complete evidence index, build/image digests, profile,
raw-result digests, exclusions, and decision. Signing is performed only after
independent-oracle reconciliation; it cannot promote missing evidence.

### 5.3 Invariant and metamorphic oracles

Continuously check these properties:

- retrying the same key and intent changes durable accounting at most once;
- retrying the same key with different intent never reuses the old result;
- the same semantic HTTP/TCP observation produces the same canonical
  `SourceResultOutcome` in every worker path; exactly one frozen route action
  applies, and only `COMPLETED` can contribute to primary supply;
- terminal upserts satisfy
  `received = inserted + updated + duplicate + rejected`, terminal validation
  satisfies `checked = valid + invalid + uncertain`, and terminal deprovision
  satisfies
  `outcomes = deprovisioned + autoExpired + ownerHandedOff + uncertain`;
  non-terminal work exposes no committed terminal category and no receipt kind
  serializes another kind's counters;
- exactly one stable Supply Policy version/controller owns each inventory scope;
  a second identity/overlap fails closed and reserved `REPLENISH` never executes;
- increasing traffic RPS does not proportionally increase Dataset PostgreSQL,
  provider, or Rabbit operations for one shared reusable Dataset;
- delaying or losing a non-authoritative reconciliation wake-up changes
  convergence time, not final authoritative revision or record safety; the
  PostgreSQL due index and periodic repair sweep remain sufficient for
  correctness;
- restarting the Orchestrator process/container changes Dataset-module
  availability within its feature RTO, not acknowledged Dataset truth,
  idempotency outcomes, schedules, claims/fences, activation state or outbox
  intent; this invariant makes no assertion about an interrupted swarm command;
- an older fence activates zero state after a newer fence exists;
- the Dataset module dispatches `DATASET_SUPPLY` only through a current opaque
  `SupplyRouteLease` issued from Swarm Controller-owned live topology; Dataset
  input cannot supply or infer exchange/routing coordinates, and an expired,
  revoked, wrong-scope/epoch/incarnation or superseded lease publishes nothing;
- every RabbitMQ swarm template/plan/start/stop/remove/config/status event uses
  the canonical `ph.control` schema, route and queue family; moving one to the
  WorkItem lane, or moving supply to `ph.control`, is always detected
  and never produces an equivalent pass;
- activating a target above both the prior target and current effective supply
  opens one new-version `TARGET_INCREASE` fill cycle; if current effective
  supply is already at/above it, no target-driven fill opens. Activating a
  lower target cannot create target-increase work,
  delete/deprovision an entity, improve Fitness, or remove a record from the
  active local view before the exact standby revision is activated;
- replaying the same target-change intent returns the same receipt and creates
  no second fill cycle, while a stale version/fence changes no target, cycle,
  reservation or worker view;
- after Orchestrator restart, the embedded Dataset module recovers only from its
  PostgreSQL authority, reclaims expired work, republishes committed outbox
  intent safely, fences stale routes and reports Dataset-ready only after its
  feature state converges; it does not infer a missing current binding,
  membership or controller from stale process memory;
- exact reconstruction of a dynamic Swarm Controller, interrupted create/
  configure/start/stop/remove command, plan, workers and topology is an
  additional `Q-PLATFORM-RECOVERY-v1` invariant under
  `DUR-009`/`OPS-009`, not an implied
  consequence of the Dataset feature invariant above;
- an optional/missing Dataset cannot make a required Dataset ready;
- authoritative Dataset fitness `PASS` is necessary but not sufficient for
  binding readiness; `FAIL` or `UNKNOWN` cannot become ready or satisfy the
  start gate, irrespective of inventory count;
- under frozen evidence and trusted time, removing required fitness evidence,
  making an assertion stricter, or reducing an eligible cohort cannot improve
  the fitness result;
- `FAIL` invalidates active use; `UNKNOWN` cannot activate a new binding or
  revision, and an already-running input can continue only on its exact
  manifest-bound prior-`PASS` view strictly before the frozen `safeUntil`;
- changing the Dataset Fitness Contract version/digest invalidates an earlier
  result for that binding until the exact new version is evaluated;
- `dataset-qualified-core` rejects `SENSITIVE_TEST` before persistence,
  provisioning, hydration or send, and only a passed exact
  `Q-SENSITIVE-ENTERPRISE-v1` profile can change that eligibility;
- adding another isolated Dataset may add its bounded lifecycle load but cannot
  borrow another scope's inventory or permissions;
- tightening validity/revocation policy can only preserve or reduce selectable
  material, never increase it;
- proof level cannot increase when a required downstream receipt is removed;
- `conservativeNow` and the accepted-time floor never move backwards across a
  process/container restart; reference-time uncertainty can only reduce
  eligibility or readiness;
- a stricter consumer validity requirement is either included in the Dataset
  module's
  demand/eligibility calculation or rejected at binding admission; adding that
  consumer cannot leave status `READY` while its eligible count is zero;
- for an `AT_LEAST_ONCE` traffic profile, redelivery preserves one immutable
  logical transaction ID and duplicate sink effects are classified and counted;
  for `SUT_IDEMPOTENT`, any replay preserves the same idempotency key and the
  external ledger shows at most one business effect;
- schema-preserving input permutations produce the same canonical mapping,
  while changing any identity, relationship, classification, or semantic value
  changes the reference result or is explicitly ignored by the versioned map;
- material-generation updates cannot mutate stable identity, relationship or
  inventory ownership; in MVP each record belongs to exactly one inventory-
  owning pool and `record_state` cannot be shadowed by `generation_state`;
- every installed snapshot equals its independently calculated manifest for
  scope, revision, schema/projection versions, ordered membership, counts and
  bytes; no page subset or delta prefix can satisfy completeness;
- superseding revisions may change convergence time but cannot increase the
  configured candidate/memory bound, activate an older revision, or make a
  decommissioned scope selectable;
- external live plus unresolved entity counts never exceed the declared
  cumulative and concurrent limits; decommission stops creation before cleanup
  and never deletes an entity with a live reservation;
- a `STATIC_REUSABLE` record becomes ineligible when its verification deadline
  passes, even if no notification arrives;
- an MVP purge/tombstone epoch dominates stale deltas, retained local
  projections, external reconciliation and rejoining worker state;
- for an admitted `Q-HA-RESTORE-v1` profile, the independently retained newest
  deletion manifest also dominates every backup/PITR point and restoration
  cannot lower its epoch;
- for a fixed eligible population and seed, selection is reproducible; changing
  worker count preserves the declared global distribution within its tolerance
  and does not silently reset every worker to the same hot subset;
- untrusted MCP client volume above a principal/Dataset/
  deployment limit is rejected or degraded inside its bulkhead and cannot
  consume traffic-path capacity; an unauthorized request sent through any
  existing generic workflow/swarm/MCP route is denied by the same product-side
  authorization/admission policy and causes zero durable mutation, dispatch or
  external effect;
- increasing bounded Dataset lifecycle work cannot starve Orchestrator command,
  authorization or health work: Dataset executor queues, JDBC permits, Rabbit
  channels, heap allocation and retained projection candidates remain within
  their frozen limits, and shared-JVM GC/control-latency thresholds remain
  inside the qualified profile;
- migration followed by supported rollback, or forward completion when rollback
  is unsafe, preserves all acknowledged truth and explicit deletion epochs.
- one canonical machine contract defines every serialized field, closed enum,
  proof fact/verdict status, error/reason code and revision/snapshot namespace;
  generated Java/JSON/TypeScript/OpenAPI/AsyncAPI representations and UI display
  mappings remain byte/semantic compatible, while prose and wireframes never
  introduce a competing wire value. Any mismatch fails `R-SSOT-DRIFT` before
  implementation or release promotion.

## 6. Testability prerequisites

The following are entry conditions for credible system qualification, not
nice-to-have diagnostics:

- injectable clock and deterministic jitter for boundary checks;
- a restart-safe trusted-time double that can independently step the wall clock,
  monotonic source, authenticated reference, uncertainty, persisted floor,
  suspend interval, and post-restart sample availability;
- provider/SUT doubles with independent append-only ledgers, scripted latency,
  status, external inventory, idempotency, duplicate business-effect detection,
  drift/invalidation, deprovisioning, ambiguity, malformed response, and rate
  limiting;
- a versioned reference mapper/data-quality oracle and independently implemented
  authorization policy evaluator with seeded-defect mutation suites;
- a canonical-contract graph and drift linter that inventories lifecycle/UI
  acceptance IDs, JSON/OpenAPI/AsyncAPI schemas, generated Java/TypeScript
  types, proof/operation enums, revision/snapshot semantics, REST documentation,
  wireframe display mappings and the acceptance registry; missing authority,
  duplicate definitions, wildcards, stale values and semantic mismatches fail
  M0 instead of being translated by a compatibility fallback;
- a versioned, independently implemented Dataset Fitness Contract evaluator
  with frozen authoritative inputs and mutation fixtures for every assertion,
  outcome and reason code, plus signed-manifest receipt controls for evaluation
  ID, input-vector digest, result and exact `safeUntil` boundaries;
- named fault hooks around every `DUR-002` boundary;
- immutable commit/operation/revision/membership IDs and stable reason codes;
- a Swarm Controller topology/lease harness that can register, renew, expire,
  revoke, supersede, forge and mismatch opaque `SupplyRouteLease` values across
  scope, binding version, operation kind, membership epoch and incarnation,
  with independent Rabbit and producer-receipt capture; it freezes the exact
  controller-owned route capability selected for the release profile, mutates
  its durability/bounds/overflow/confirm/ack/redelivery/delivery-limit/DLQ
  settings, and detects silent delete, recreate, downgrade or fallback;
- a two-stage producer-start harness that begins from `STOPPED`, `STARTING`,
  stale-`RUNNING`, current-`RUNNING`, stop-racing and rebind-racing states;
  captures the canonical `ph.control` start command/outcome and exact producer
  status; and proves no reservation/outbox publication can precede a fresh
  successful start outcome, input-ready observation and matching current route
  fence. A current already-running producer may satisfy the idempotent
  ensure-running decision without a duplicate start command;
- an embedded-module recovery harness that can terminate the Orchestrator
  process/container at every Dataset commit, claim, schedule, outbox, publish,
  activation and reconciliation boundary while independently observing
  PostgreSQL and provider/Rabbit effects; it distinguishes feature convergence
  from the outcome of any interrupted swarm command and supports repeated
  unhealthy/startup-crash cycles before readiness;
- only when exact interrupted swarm-lifecycle recovery is claimed, a durable
  desired-runtime observer and separate platform recovery harness that can stop
  the dynamic Swarm Controller, its Orchestrator owner, Docker and the host at
  every reconciliation boundary; inject competing/stale controller
  incarnations; and compare the desired row, exact plan/config/worker/topology
  state, active incarnation, route leases and Docker inventory;
- executable compatibility tests for the common Dataset contracts and
  serialization plus worker-SDK local hydration/atomic swap/safe-horizon and
  manager-SDK guard/readiness behaviour, run against the embedded module's
  official ports rather than private implementation classes;
- profile-gate controls that attempt classification downgrade, stale evidence,
  label/config mutation and direct API/MCP admission of `SENSITIVE_TEST` while
  only `dataset-qualified-core` is active;
- a cross-path authorization harness that presents the same allowed and denied
  Dataset scopes through the Dataset MCP status reads, bounded proof creation and every
  existing generic workflow/swarm/MCP route able to reference a Dataset; it
  compares the product decision with the independent policy evaluator and
  observes the durable Dataset, dispatch and external-effect ledgers to prove
  every denied attempt has zero effects;
- immutable offered-transaction and SUT idempotency keys, broker redelivery/
  replay controls, and a sink able to distinguish delivery attempts from unique
  business effects;
- request-thread remote-I/O detection and per-component request counters;
- scoped read-only database invariants and schema-validated Rabbit capture for
  separately approved persistence/protocol component tests;
- deterministic snapshot page/delta delay, corruption, duplication, omission,
  supersession storms and token-expiry controls, plus an independently generated
  snapshot manifest and bounded candidate/memory observations;
- deterministic selector-before-materializer scheduling, Dataset/auth
  activation-ack incarnation faults, selector/stage attestation mutation/
  reorder/replay, prepared-model/destination mutation, and auth-revision
  rotation/restart reconstruction;
- final send-gate pause/drain/socket-stall hooks around provider invalidation;
- source direct-committer and refresh-resolver capture proving sensitive values
  never enter the returned WorkItem/generic output;
- auth token-exchange/revocation-feed/JWKS fault controls and host
  swap/hibernation/crash-artifact inspection;
- an ephemeral test CA and identity harness able to vary SAN, audience, subject,
  actor chain, binding, certificate/token age, issuer/JWKS version, partial
  rollout, renewal, revocation and expiry; sensitive sender-constrained profiles
  additionally exercise their mTLS key proof and any declared DPoP ingress
  proof/nonce/replay rejection;
- published canonicalization vectors for the selector/stage/proof protocols,
  including duplicate and case-varied HTTP headers, whitespace, Unicode
  normalization, query ordering, empty values, binary payloads and every
  supported non-HTTP protocol representation;
- external-entity cumulative inventory, cleanup/decommission, tombstone epoch
  and stale-worker-rejoin controls, plus separate destructive restore controls
  when `Q-HA-RESTORE-v1` is claimed;
- hierarchical limit/bulkhead telemetry and deterministic load for definitions,
  bindings, hydration, refresh, bounded MCP status reads and proof creation,
  scans, staging and audit;
- per-module and whole-Orchestrator JVM telemetry for CPU, heap allocation and
  occupancy, GC pause/count, threads, executor queue/active/rejection counts,
  JDBC pool wait/use, Rabbit channels/confirms, lock/WAL pressure and
  Orchestrator command/authorization/health latency;
- explicit liveness-remediation ownership with a controllable running-but-
  unhealthy state, supervisor/controller events and crash-loop backoff;
- mixed-version deployment, expand/contract migration, rollback/forward-repair,
  old-key/schema and downgrade-rejection fixtures;
- selection-policy reference sampling with deterministic seeds and per-record/
  category/worker distribution capture;
- unique canary generation plus exact, encoded, and transformed sink scanning;
- resource metrics, open-loop load generation, raw histograms, and immutable run
  manifests;
- a browser/API performance harness with frozen maximum authorised Dataset,
  binding, consumer, operation, evaluation, proof-fact and worker-report
  cardinalities; maximum identifier/reason lengths; concurrent principals;
  query-plan, rows-read/returned, bytes, temporary-spill, lock, JDBC-pool and
  render/task observations; and slow/hidden/offline client controls;
- Docker/container inventory and restart-event capture; and
- a machine-readable parent-and-child-assertion-to-evidence index, immutable
  run-manifest digests, detached evidence signing, trusted timestamping and
  clean-room signature verification for exported enterprise claims.

Before contract freeze, the custom Dataset fencing, supply-route lease,
activation, PostgreSQL recovery, outbox, trusted-time, supersession, replay and
deletion-epoch protocols shall have a small executable state model (TLA+/PlusCal
or an equivalently reviewable model plus model-based tests). It shall explore
concurrency, crash/restart, message loss/reorder, stale owner/lease/incarnation,
clock uncertainty and rollback. Desired Swarm Controller-runtime ownership is
added to that model only for the separate exact lifecycle-recovery claim. The
model is not implementation proof; its counterexamples become regression
scenarios and its assumptions are listed in the qualification manifest.

If a critical behaviour cannot be controlled or observed, its result is
unknown. Adding an unsafe production backdoor to improve testability is not an
acceptable trade.

## 7. Risk-based exploratory charters

A charter states a mission and risk, not a fixed script. The tester may follow
important observations and records coverage, variations, evidence, and new
questions during debrief.

| Charter | Mission and principal attacks | Oracles/evidence | Initial timebox |
|---|---|---|---|
| `CH-01 Binding` | Explore definition/binding/vars materialisation; vary missing/wrong/extra/native values, moving aliases, scope, policy version, and unsupported allocation around create/update/restart | frozen plan, official API, container inventory, audit | 90 min |
| `CH-02 Supply` | Challenge watermark/target/capacity accounting with zero/one/50,000/55,000 inventory and an explicit `(prior target, effective supply, new target)` oracle. Include `(30k,20k,40k)` one `TARGET_INCREASE`, `(30k,60k,40k)` no fill, `(50k,20k,30k)` no target-increase then only the new low-watermark rule, unchanged-target policy replacement with an open fill, initial policy with pre-existing inventory, and rejected infeasible decreases/increases. Restart at hold/prepared/CAS/applied/fill-cycle boundaries; vary concurrent controllers, duplicate/stale intent, claim expiry and late success; seek a missed/duplicate/stranded cycle, unsafe local-view removal or silent external deletion | independent decision table and demand/fill-cycle model, schedule/activation rows, provider and worker-selection ledgers, DB constraints/receipts | 210 min |
| `CH-03 Trusted time` | Try to make expired/revoked material usable through host rollback, restart, lost/stale reference, suspend, forward/backward step, uncertainty growth and exact-boundary scheduling; challenge the persisted floor and fail-closed readiness | independent reference clock, restart trace, persisted-floor observation, request sink | 150 min |
| `CH-04 Fence/source resume` | Pause an old producer across claim expiry/reassignment; fail/restart between parent and child source steps; force ambiguous outcomes around every checkpoint/direct commit/refresh resolve. Run the preferred `DATASET_UPSERT` output and the permitted `managedDatasetPublisher` interceptor against the same contract vectors; fail before/after the durable receipt and before primary-output publication, proving stable idempotency, equivalent receipts, fail-closed ordering and no log-and-continue. Seek WorkItem leakage, duplicate parent, broken relationship, stale activation, divergent output/interceptor semantics, or blind retry | provider ledger, serializer and primary-output capture, fence/checkpoint/operation history, PostgreSQL receipts, resulting SUT flow | 210 min |
| `CH-05 Snapshot` | Corrupt, truncate, duplicate, reorder, overlap, omit and expire snapshot/delta pages and cursors; vary page boundaries, manifest counts/bytes/digest, schema/projection version, scope and delta-chain ancestry; restart during build/swap and seek partial, mixed, rolled-back or amplified views | controlled snapshot server, independent manifest, heap/thread counters, selection sink | 180 min |
| `CH-06 Outbox` | Crash at each database commit, outbox claim, Rabbit publish/return/confirm, delivery, durable producer claim/result and acknowledgement boundary; combine connection loss, queue full/TTL/DLQ, duplicate delivery and relay restart | DB invariant, Rabbit capture, producer receipt and outbox/revision history | 180 min |
| `CH-07 Dataset restart/liveness` | Restart and repeatedly crash the Orchestrator process/container, including failures before health becomes ready, at every embedded Dataset commit/claim/schedule/outbox/activation boundary and during steady traffic; distinguish feature reconciliation and valid local-worker continuation from any interrupted platform command, and seek premature ready, stale dispatch, loss or duplicate effect | PostgreSQL/outbox/claim/schedule invariants, supervisor/Docker events, worker safe-horizon status and operation/provider/Rabbit ledgers | 180 min |
| `CH-08 Hot path/shared JVM` | Seek any central call, Dataset-induced CPU or heap-allocation spike, GC pause, thread/executor/JDBC/Rabbit-pool starvation, secret serialization, queue growth, control-latency regression or journal interference across feature-off/idle/active modes in the same Orchestrator JVM | I/O detector, open-loop load, JVM/executor/pool telemetry, control-operation probes and Rabbit/DB/journal telemetry | one baseline cycle |
| `CH-09 Scale` | For an enterprise claim, step 10/20/30/40/50 shared then isolated swarms; force simultaneous hydration and restart ten while twenty run; find the repeated knee, bulkhead saturation and noisy neighbour without generalising across profiles | complete profile manifest, raw rates/lag/histograms/resources | multiple controlled runs |
| `CH-10 Egress/input` | Attempt SSRF through URL/DNS/IP/redirect/metadata/socket and rebinding paths; vary oversized/deep/compressed/malformed provider content, unsupported slot contexts and control-sequence/context injection; seek parser/proxy bypass and resource amplification | proxy and external sink capture, parser/resource telemetry, Dataset-module audit | 150 min per protocol |
| `CH-11 Key lifecycle` | Rotate KEKs, DEKs, certificates and credentials during traffic. Challenge missing or mismatched Docker secrets, manifest/database disagreement, concurrent and repeated rotation, nonce allocation around crash/restart, historical-key loss, early retirement and ciphertext or AAD tampering. Require fail-closed startup, atomic manifest activation, decryptability of retained data and zero nonce reuse. Restore remains outside the core profile | database and key-manifest history, secret inventory, decrypt/tamper oracle and traffic/provider sinks | 180 min |
| `CH-12 MCP proof` | Treat the client as untrusted: verify the exact three-tool catalogue; require VIEW plus `dataset:proof:create` for proof creation; test idempotent replay, conflicting key reuse, rate/concurrency/storage quotas and proof-read scope; remove or contradict each evidence stage; inject hostile identifiers; and try to obtain values or amplify privilege. Exercise denied Dataset scopes through existing generic routes and prove zero lifecycle, dispatch or provider effect | independent evidence chain and policy evaluator, MCP contract/audit, proof store, Dataset/dispatch/provider ledgers | 120 min |
| `CH-13 Redis` | Run existing non-managed Redis paths and explicit `REDIS_COLLECTION_V1` Dataset registrations separately. Reject missing/mismatched Space/adapter settings and unsupported package capabilities; restart/wipe Redis and PostgreSQL independently; prove no adapter fallback, cross-adapter state claim or inflated Redis durability/proof claim | adapter-scoped golden behaviours, Space/package/registration/binding validation, Redis/PostgreSQL before/after state and recovery receipts | 150 min plus regression |
| `CH-14 Endurance` | Run the named 24-hour profile with refresh, source outage/recovery, rotations, steady-state Orchestrator restart waves, pool depletion/refill, retention, disk pressure and proof polling; do not schedule interrupted swarm commands unless the separate platform-recovery claim is under test | full evidence pack, feature/platform event classification and trend/anomaly review | 24 h plus debrief |
| `CH-15 Refresh/validity demand` | Vary policy minimum and multiple consumers' stricter remaining-validity requirements around every boundary; align expiry, throttling and policy changes; try to obtain `READY` while one consumer has zero eligible supply or to starve it behind weaker demand | deterministic demand model, provider ledger, per-consumer eligibility/status, traffic sink | 150 min |
| `CH-16 Activation/attestation` | Race Dataset/auth activation and worker incarnation; forge acknowledgements; mutate/replay/reorder selector and request-builder envelopes, keys, canonical forms, prepared model, payload and destination; use duplicate headers, Unicode and binary vectors and restart at every chain transition | independent canonical vectors, both activation ledgers, MAC/model mutation, network sink | 210 min |
| `CH-17 Data semantics` | Challenge executable schema/mapping with null/missing/default/coercion, unknown fields, natural-key collisions, parent/child order, category/classification, projection and partial batch failure. Mutate HTTP status/body/header and TCP framing/charset/text/binary-decoder results across completed, conclusive wrong-state, call-failed, missing, unknown, pending and ambiguous boundaries; remove/duplicate/reorder routes, inject dynamic/unauthorised targets, and compare output versus interceptor classification. Seed plausible wrong mappings and seek any non-completed contribution to primary supply | independent reference mapper and cross-protocol result classifier, provider/source ledger, authorised-target/operation/DB invariants, mutation score | 240 min per schema/result-policy version |
| `CH-18 Traffic replay` | Redeliver/requeue/replay before and after final send, acknowledgement, timeout and restart; reuse and mutate transaction/idempotency keys; distinguish attempts from external effects and try to make an `AT_LEAST_ONCE` result look exactly once | broker capture, immutable offered-ID ledger, external effect ledger, receipt aggregates | 150 min per replay profile |
| `CH-19 External lifecycle` | Run create/refresh/replace/decommission through concurrent reservations, provider ambiguity, cumulative/rolling limits, partial cleanup, already-deleted and retained-owner entities; apply stale local deltas/projections and rejoin workers after tombstone, seeking resurrection or deletion-in-use | provider inventory, Dataset-module tombstone/operation ledger, worker projection and external cleanup reconciliation | 210 min |
| `CH-20 Static drift` | Change, close, block, reclassify and delete `STATIC_REUSABLE` entities externally with and without notification; delay validation past its deadline and challenge safe degradation/recovery during 24/7 traffic | independent provider status, verification deadline, selection/request sink | 120 min plus soak |
| `CH-21 Revision churn` | Commit revisions faster than workers can hydrate; combine supersession, slow/dead worker, delayed periodic reconciliation, large delta, memory pressure and activation timeout; test coalescing/skipping rules, candidate and retained-old bounds, fairness and convergence without requiring an extra Rabbit lane | independent manifest/revision model, Dataset API/reconciliation history, memory/lag/ack aggregates and sink | 180 min |
| `CH-22 Identity/PKI` | Challenge bootstrap, SAN/audience/subject/actor/binding, partial CA/JWKS rollout, renewal overlap, expiry, revocation and stale feed; prove bearer-only sensitive admission fails, replay an mTLS-bound token from another process/key, and attack DPoP proof/nonce/replay where declared; inspect every private-key owner and fallback path | test CA/token service, independent policy oracle, packet/config/audit capture | 210 min per security profile |
| `CH-23 TCB/leakage` | Compromise or misconfigure the shared Orchestrator process/container, its Docker-control surface, one final processor, host or debug surface; attempt cross-Dataset projection/key access and exfiltration through logs, dumps, swap, hibernation, volumes, IPC, metrics, MCP and crash artifacts; measure scope/time/record/control-plane blast radius and challenge whether the combined boundary is acceptable for the named profile | access matrix, canary scanner, process/host/container/image/Docker-authority inspection, key/projection inventory and independent security review | 210 min per profile |
| `CH-24 Control/MCP abuse` | Flood definitions, bindings, hydration, refresh, status reads and bounded proof creation by one and many principals; use hostile pagination, retries, cancellation and idempotency-key conflicts; repeat the `CH-12` cross-path deny under load and seek authorization bypass, unbounded evidence, lifecycle/provider effects or traffic-path resource theft | independent policy oracle, Dataset/proof/dispatch/provider ledgers, per-principal/scope bulkhead metrics, traffic non-interference | 180 min |
| `CH-25 Upgrade/rollback` | Exercise clean install, expand/migrate/contract, rolling mixed versions, interrupted migration and policy/key/schema change. Reject unsupported downgrade, preserve data across supported rollback, require forward repair where declared and requalify any pinned encryption or qualification-contract change | migration history, key-manifest and qualification records, before/after database/provider invariants, compatibility matrix and traffic sink | 210 min per supported path |
| `CH-26 Selection realism` | Exercise round-robin/uniform/weighted policies over uneven classes, records entering/leaving and worker-count/seed changes; look for hotspots, correlated worker resets, starvation and mismatch between configured and observed traffic mix | reference sampler, per-record/category/worker distributions, external sink | 180 min plus load blocks |
| `CH-27 Game day` | Combine asymmetric network partitions, slow links, clock uncertainty, partial certificate/key rollout, stale-node rejoin, broker/database degradation and operator error while traffic continues; test decision authority, communications, safe-idle and recovery runbooks | independent incident timeline, supervisor/network events, all durable/external ledgers | 4 h plus debrief |
| `CH-28 Evidence authenticity` | Remove, replace, backdate and selectively regenerate raw artifacts; sign a `CoreQualificationRecord/v1` with the wrong, expired or purpose-mismatched identity; mutate child/build/image/deployment/registry/pack digests; replay a record for another candidate; and inject a half-committed activation. Perform clean-room signature and manifest verification | independent signature/digest verifier, candidate and atomic activation receipts, immutable evidence index and reviewer identity chain | 150 min |
| `CH-29 Restore non-resurrection` | Only for `Q-HA-RESTORE-v1`, restore backup/PITR points around newer deletions and challenge the profile's explicitly selected external deletion authority with absent, older, corrupt or divergent state. Rejoin stale workers and reconcile provider effects before selection. Core qualification makes no restore claim | destructive isolated restore, external deletion-authority records, provider inventory and traffic sink | 240 min per restore profile |
| `CH-30 Supply activation/route/topology` | Start from stopped, starting, stale-running, current-running, stop-racing and rebind-racing producers. Prove an idempotent ensure-running decision uses canonical `ph.control` whenever lifecycle change is required and that correlated success plus fresh exact-incarnation `RUNNING`, input-ready and route-fence observations precede reservation/outbox publication. Deny, delay, reorder and contradict each observation and require zero supply. Register, renew, expire, revoke, replay and supersede a `SupplyRouteLease`; mutate scope/binding version/operation kind/epoch/incarnation, selected route capability and topology during dispatch; attempt raw/inferred/fallback routes; vary durability, bounds, overflow, confirm, ack/redelivery, delivery limit and DLQ settings; seek silent delete/recreate/downgrade or `PRECONDITION_FAILED` loops. Move each canonical swarm control event off `ph.control`, and inject supply into control queues, to prove the exact three-lane schema/order boundary and control responsiveness | independent Swarm Controller lifecycle/topology/lease ledger, correlated control command/outcome/status capture, exact schema-validated Rabbit lane/capability/route history, producer receipt and provider ledger | 270 min |
| `CH-31 Q-PLATFORM-RECOVERY-v1` | Only when `Q-PLATFORM-RECOVERY-v1` is requested, stop a dynamic Swarm Controller, its Orchestrator owner, Docker and host before/after desired-runtime and every create/config/start/stop/remove command boundary; inject competing/stale controller incarnations, missing/duplicate actual instances, running-unhealthy and crash loops; attempt mutation/dispatch before exact plan/config/worker/topology/lease convergence | durable command/desired-runtime rows, Orchestrator/controller journals, Docker events/inventory, controller/route/worker state and provider/DB ledgers | 210 min |
| `CH-32 Profile gate` | Enter only the separately authorised `QUALIFICATION_CANDIDATE`; remove or fail one required child at a time; forge, replay or stale the signed qualification record; and change build/image/deployment digests before activation. Verify only exact signature and manifest-digest agreement plus atomic database activation enables capability. Under `dataset-qualified-core`, submit `SENSITIVE_TEST`, downgrade classifications and repeat with one required enterprise child removed | independent applicability/signature/digest evaluator, candidate token and activation audit, official admission/status/MCP APIs, database/Rabbit/canary scan and signed manifest | 210 min per release gate |
| `CH-33 Dataset fitness` | With adequate counts, attempt start and selection under wrong cohort, broken relationships, stale/short-lived records, missing provenance/classification, wrong environment/use, `FAIL`, `UNKNOWN`, forged/mismatched receipt and superseded contract. Cross the exact `safeUntil`, require zero unsafe sink use, recover to `PASS`, and repeat through refresh, outage and restart | frozen Fitness Contract, independent evaluator, authoritative Dataset/source/provenance snapshots, trusted time, manifest/activation receipt, worker I/O detector, readiness and external traffic sink | 180 min per contract version |
| `CH-34 UI and binding contract coherence` | Compare every Dataset UI endpoint/DTO/field/status/reason and the bundle-local requirement → per-scenario mapping → runtime-resolved binding chain with canonical schemas, REST docs, generated types, proof service, operation ledger, acceptance registry and wireframe mappings. Inject another scenario ID, missing/extra/duplicate mappings, compatibility shims, client defaults, invented states and revision confusion | `OR-REGISTRY`, bundle-isolation and cross-language TCKs, drift graph, release-bundle/import scan and schema mutation report | 150 min per contract baseline |
| `CH-35 UI truth, time and cross-view consistency` | Across inventory, all five detail tabs, Dataset dependencies and Runtime Inspector, remove, contradict, supersede and stale each authority. Cross exact validity/`safeUntil`, snapshot-token, projection-lag, operation, Fitness/prior-PASS and applied-revision boundaries; seek any client-derived decision, zero/default, proof promotion or disagreement between views/API/MCP | `OR-UI`, `OR-FITNESS`, `OR-PG`, `OR-RABBIT`, `OR-WORKER-SINK`, canonical proof and operation ledgers | 180 min |
| `CH-36 UI authorization, enumeration and cache isolation` | Vary principal, role, environment/Dataset/partition/pool/swarm, page/cursor/search/facet/summary/proof target and identity switch; inspect browser memory/storage/network and shared caches. Seek hidden-scope totals, existence side channels, stale-principal reuse, hostile-label execution, client-only denial or any durable/dispatch/provider effect from a denied read | `OR-AUTHZ`, `OR-UI`, `OR-PG`, `OR-EXT-EFFECT`, side-channel timing samples and canary scan | 180 min |
| `CH-37 UI accessibility and responsive safety` | Exercise every route/state with keyboard only, focus restoration, accessible names/roles/values, screen reader, forced colours, light/dark modes, required desktop/tablet/mobile widths, 200% zoom and 320-CSS-pixel reflow. Use longest identifiers/reasons, localisation expansion and reduced motion; seek a hidden/truncated safety fact, undiscoverable tab, colour-only distinction, inaccessible disclosure or two-dimensional page scroll | `OR-UI`, accessibility tree, Firefox keyboard/reflow captures, screen-reader notes, contrast/target measurements and DOM order | 180 min plus assistive-technology pass |
| `CH-38 UI polling, proof and shared-resource non-interference` | Run the frozen maximum authenticated sessions at the minimum refresh interval while active traffic, refresh, proof and control work execute. Vary hidden/offline/slow clients, restart, cancellation, jitter, 429/`Retry-After`, bounded 503, proof interval size and hostile retry loops; seek overlapping polls, timestamp extension, unbounded evidence, pool starvation or measurement distortion | `OR-RESOURCE`, `OR-UI`, open-loop paired traffic, server/read-bulkhead metrics, query plans and proof/effect reconciliation | one paired performance cycle plus soak |
| `CH-39 Operator adverse-state and remediation journey` | Traverse loading, background refresh, stale, partial, reconciling, empty, denied, limited, unavailable, incompatible, failed, starved and uncertain states from inventory through detail/Inspector. Ask operators to identify authority, impact on new starts versus running traffic, safe next action, owner and evidence link; seek ambiguous colour/copy, dead ends, unsafe implied mutation or a remediation that cannot be performed through the authorised admin/API/runbook surface | `OR-UI`, incident timeline, operation/binding/worker ledgers, runbook/action-owner review and task-based operator observation | 150 min |
| `CH-40 Producer lifecycle, cancellation and stranded-resource coupling` | Interrupt provisioning, refresh, validation, replacement, deprovision and `CancelSupplyOperation/v1` before claim, during claim/provider call, after effect, during ambiguous/late receipt, restart, redelivery and cleanup. Race stale fence/version, duplicate/conflicting idempotency and denied scope. Verify accepted intent blocks claim/requeue but retains reservation; only conclusive no-effect becomes `CANCELLED`; real effects receive normal typed accounting; ambiguity stays `UNCERTAIN`; pause/policy/timeout/shutdown/decommission synthesize no intent. Remove/restart the producer/controller while external entities remain and verify UI/API separate durable operation truth from live observation, expose cleanup owner/deadline and never claim deprovision from container absence | official admin command/receipt and independent auth oracle, `OR-EXT-EFFECT`, `OR-PG`, `OR-RABBIT`, claim/route/controller events, tombstone/decommission ledger and `OR-UI` | 240 min plus cleanup window |

### 7.1 Charter traceability

| Charter | Principal risks | Acceptance focus |
|---|---|---|
| `CH-01` | `R-SCOPE`, `R-PROJECTION` | `FUN-001`, `FUN-004`--`FUN-007` |
| `CH-02` | `R-CAPACITY`, `R-LIVE-RESIZE`, `R-DURABLE` | `FUN-002`--`FUN-003`, `FUN-006`, `FUN-008`, `LIF-001`, `LIF-007`--`LIF-009`, `LIF-013`, `PER-005`, `OPS-004` |
| `CH-03` | `R-EXPIRY`, `R-TIME-ROLLBACK`, `R-ROTATION-RACE` | `LIF-002`--`LIF-003`, `DUR-001`, `DUR-011`, `OPS-003` |
| `CH-04` | `R-DURABLE`, `R-REFRESH`, `R-AUTH-PATH` | `FUN-009`, `LIF-005`--`LIF-006`, `DUR-002`, `SEC-007` |
| `CH-05` | `R-PROJECTION`, `R-SNAPSHOT-INTEGRITY`, `R-COLD-START` | `FUN-004`, `DUR-002`, `DUR-010`, `EVD-001`, `PER-005` |
| `CH-06` | `R-DURABLE`, `R-SHARED-INFRA` | `DUR-002`--`DUR-003`, `PER-008` |
| `CH-07` | `R-DURABLE`, `R-LIVENESS-RECOVERY`, `R-TIME-ROLLBACK` | feature-scoped children of `DUR-001`--`DUR-005`, plus `OPS-002`--`OPS-005` and `OPS-007`; expressly not `DUR-009`/`OPS-009` |
| `CH-08` | `R-MEASURE`, `R-SHARED-INFRA`, `R-CONTROL-DOS` | `PER-001`--`PER-005`, `PER-008`, `PER-010` |
| `CH-09` | `R-CLAIM-INFLATION`, `R-COLD-START`, `R-SHARED-INFRA` | `LIF-008`, `PER-006`--`PER-007` |
| `CH-10` | `R-EGRESS`, `R-CONTROL-DOS` | `FUN-005`, `SEC-011`, `PER-008`, `PER-010` |
| `CH-11` | `R-KEY-LOSS`, `R-LEAK` | `SEC-004`--`SEC-006`, `SEC-010`, `SEC-013`, `SEC-016` |
| `CH-12` | `R-FALSE-PROOF`, `R-EVIDENCE-LOAD`, `R-SCOPE`, `R-AUTHZ-ORACLE` | `SEC-003`, `SEC-008`, `EVD-001`--`EVD-004`, `EVD-006` |
| `CH-13` | `R-REDIS-REGRESSION` | `DUR-006`, `EVD-005` |
| `CH-14` | all P0/P1 for the named profile | `PER-001`, `PER-009`, applicable durability/security/operations IDs |
| `CH-15` | `R-REFRESH`, `R-VALIDITY-STARVATION`, `R-EXPIRY` | `LIF-001`--`LIF-004`, `LIF-010`, `PER-009`, `OPS-003` |
| `CH-16` | `R-ACTIVATION`, `R-AUTH-PATH`, `R-CANONICAL-MISMATCH`, `R-ROTATION-RACE` | `FUN-004`--`FUN-006`, `LIF-003`, `SEC-014`, `EVD-001` |
| `CH-17` | `R-DATA-QUALITY`, `R-DURABLE`, `R-SCOPE` | `FUN-001`--`FUN-003`, `FUN-009`--`FUN-010`, `SEC-001` |
| `CH-18` | `R-TRAFFIC-REPLAY`, `R-DURABLE`, `R-FALSE-PROOF` | `FUN-005`, `FUN-011`, `DUR-002`--`DUR-003`, `EVD-004`, `EVD-006` |
| `CH-19` | `R-EXTERNAL-LIFECYCLE`, `R-DELETION-RESURRECTION`, `R-CAPACITY` | `LIF-001`, `LIF-005`, `LIF-012`, `DUR-010`, `OPS-004` |
| `CH-20` | `R-STATIC-DRIFT`, `R-EXPIRY`, `R-REFRESH` | `LIF-002`--`LIF-004`, `LIF-011`, `OPS-003` |
| `CH-21` | `R-REVISION-CHURN`, `R-SNAPSHOT-INTEGRITY`, `R-COLD-START` | `FUN-004`, `DUR-010`, `PER-005`, `PER-008` |
| `CH-22` | `R-IDENTITY-LIFECYCLE`, `R-AUTHZ-ORACLE`, `R-SCOPE` | `SEC-003`, `SEC-005`, `SEC-008`, `SEC-012` |
| `CH-23` | `R-TCB-BLAST`, `R-LEAK`, `R-AUTH-PATH` | `SEC-002`, `SEC-005`--`SEC-007`, `SEC-015` |
| `CH-24` | `R-CONTROL-DOS`, `R-EVIDENCE-LOAD`, `R-AUTHZ-ORACLE` | `PER-008`, `PER-010`, `SEC-003`, `SEC-008`, `EVD-006` |
| `CH-25` | `R-UPGRADE`, `R-DURABLE`, `R-KEY-LOSS` | `DUR-002`, `DUR-004`, `OPS-001`--`OPS-002`, `OPS-008`, `SEC-016` |
| `CH-26` | `R-SELECTION-BIAS`, `R-MEASURE` | `FUN-008`, `FUN-011`, `PER-001`--`PER-004`, `EVD-006` |
| `CH-27` | all applicable P0/P1 recovery and operability risks for the named profile | `FUN-012`, feature-scoped `DUR-001`--`DUR-007` and `DUR-010`--`DUR-011`, `OPS-002`--`OPS-008`, applicable lifecycle/security IDs; add `DUR-009`/`OPS-009` only for `Q-PLATFORM-RECOVERY-v1` |
| `CH-28` | `R-EVIDENCE-INTEGRITY`, `R-FALSE-PROOF` | `EVD-001`--`EVD-005`, `EVD-008`--`EVD-010` |
| `CH-29` | `R-RESTORE-RESURRECTION`, `R-KEY-LOSS`, `R-DURABLE` | `DUR-008`, `DUR-012`, `SEC-009`--`SEC-010` |
| `CH-30` | `R-SUPPLY-ROUTE`, `R-SUPPLY-TOPOLOGY`, `R-CONTROL-PLANE-BYPASS`, `R-SCOPE`, `R-DURABLE` | `FUN-012`, `DUR-003`, `PER-008`, `OPS-002`, `OPS-006` |
| `CH-31` | `R-RUNTIME-OWNER`, `R-CONTROLLER`, `R-LIVENESS-RECOVERY` (`Q-PLATFORM-RECOVERY-v1` only) | platform-scoped children of `DUR-004`--`DUR-005`, plus `DUR-009`, `OPS-007`, `OPS-009` |
| `CH-32` | `R-SENSITIVE-ADMISSION`, `R-CLAIM-INFLATION`, `R-LEAK`, `R-EVIDENCE-INTEGRITY` | section 27.4, applicable MVP rows, `SEC-002`, `SEC-004`--`SEC-005`, `SEC-007`, `SEC-012`--`SEC-015`, `EVD-008`--`EVD-010` |
| `CH-33` | `R-FITNESS`, `R-DATA-QUALITY`, `R-FALSE-PROOF`, `R-MEASURE` | `FUN-010`, `FUN-013`, `LIF-001`--`LIF-004`, `OPS-003`, `PER-001`, `EVD-001`--`EVD-004` |
| `CH-34` | `R-SSOT-DRIFT`, `R-UI-FALSE-STATE` | Exact `DSUI` IDs whose registry `primaryCharter` is `CH-34`; principally contract, data and semantic requirements under `FUN-014` |
| `CH-35` | `R-UI-FALSE-STATE`, `R-UI-STALE`, `R-FITNESS`, `R-FALSE-PROOF` | Exact `DSUI` IDs whose registry `primaryCharter` is `CH-35`; principally authority, view and state requirements under `FUN-014` |
| `CH-36` | `R-UI-SCOPE-LEAK`, `R-SCOPE`, `R-AUTHZ-ORACLE` | Exact `DSUI` IDs whose registry `primaryCharter` is `CH-36`; principally ingress, proof-read, server-filtering and security requirements under `FUN-014` |
| `CH-37` | `R-UI-EXCLUSION`, `R-UI-FALSE-STATE` | `DSUI-A11Y-001`, `DSUI-A11Y-002`; supporting view/state mappings are explicit in the registry |
| `CH-38` | `R-UI-LOAD`, `R-MEASURE`, `R-EVIDENCE-LOAD`, `R-CONTROL-DOS` | Exact `DSUI` IDs whose registry `primaryCharter` is `CH-38`; principally `DSUI-ARCH-002` and `DSUI-PERF-001`--`006` |
| `CH-39` | `R-OPERABILITY`, `R-UI-STALE`, `R-UI-EXCLUSION` | Supporting charter for the exact view/state/accessibility IDs listed in the registry plus `OPS-001`--`OPS-009` as applicable; it cannot pass an unimplemented admin control |
| `CH-40` | `R-CANCEL-AMBIGUITY`, `R-EXTERNAL-LIFECYCLE`, `R-RUNTIME-OWNER`, `R-CONTROLLER`, `R-UI-FALSE-STATE` | `LIF-005`, `LIF-012`, `LIF-014`, `DUR-003`--`DUR-005`, `OPS-004`, `OPS-007`, and supporting `DSUI-VIEW-004`, `DSUI-VIEW-005`, `DSUI-VIEW-007` mappings |

When a charter finds a new credible risk, stop following the table mechanically:
capture a minimal reproducer, assess blast radius, update the model/register,
and create or retarget a charter.

## 8. Corner and variation checklist

Use this checklist during both automated design and exploratory sessions:

- **Cardinality**: zero, one, two, many, policy maximum, one beyond maximum;
  empty page, final partial page, largest projection, many subscribers.
- **Boundaries**: just below/equal/just above watermarks, target, capacity,
  `usableFrom`, `refreshAt`, `usableUntil`, expiry, credential/revocation/offline
  horizon, consumer versus policy validity, verification deadline, accepted-time
  floor, lease, retry, rolling/cumulative create limit, retention, RTO, queue
  byte/count, disk threshold.
- **Order**: duplicate, omit, delay, reorder, replay, supersede; before/after
  commit, confirm, return, ack, provider effect, checkpoint, revision, swap.
- **Concurrency**: two owners, many producers, two controllers, worker churn,
  policy activation during supply, refresh during purge, restart during rotate,
  simultaneous cold start, slow/paused former owner, stricter and weaker shared
  consumers, decommission during reservation/send, supersession during swap,
  competing Dataset claims/outbox relays; for the deferred platform claim,
  competing desired Swarm Controller-runtime reconcilers and duplicate/missing
  actual controller containers.
- **Identity/scope**: absent, stale, forged, wildcard, similar-looking alias,
  wrong environment/Dataset/partition/pool/swarm/projection/audience/epoch.
- **Routing/topology**: absent, expired, revoked, forged, replayed or superseded
  `SupplyRouteLease`; wrong source-binding version, operation kind, membership
  epoch, producer incarnation, exchange or routing key; topology rebind between
  lease validation and publish; handcrafted/inferred route input; stopped,
  starting or stale-ready producer; start timeout/denial; supply-before-ready;
  wrong durability, bound, overflow, confirm, ack/redelivery, delivery-limit or
  DLQ settings for the selected controller-owned route; `PRECONDITION_FAILED`;
  and prohibited delete/recreate/downgrade/fallback.
- **Release profile**: core-only classification downgrade or alias, stale/forged
  qualification evidence, build/image/profile drift after pass, partial child
  coverage and UI/API/MCP claim above the highest passed gate.
- **Representation**: null/empty/Unicode/long/hostile labels, wrong native type,
  unknown/extra JSON property, duplicate/case-varied header, Unicode
  normalization, query/field ordering, binary/empty payload canonicalization,
  encoded canary, malformed or oversized provider/Rabbit/page data.
- **Data semantics**: natural-key collision, stable identity versus generation,
  wrong parent/child, many-to-one and one-to-many cardinality, missing/default/
  null/coercion, classification/projection mismatch, per-item versus batch
  atomicity, partial rejection, cross-category skew.
- **Fitness**: sufficient count with the wrong cohort, broken relationship,
  stale record, inadequate remaining validity, unverified provenance or
  classification, forbidden environment/use, missing/contradictory evidence,
  `PASS`/`FAIL`/`UNKNOWN`, contract supersession and evaluation outage.
- **Time/restart**: reference unavailable/stale, host wall-clock rollback,
  monotonic reset, suspend/hibernate, forward/backward step, maximum uncertainty,
  restart before a fresh sample, old persisted floor, certificate/time bootstrap
  dependency cycle; Orchestrator termination before/after every Dataset durable
  boundary, repeated startup failure before ready, valid local-worker
  continuation, and explicit separation from an interrupted swarm command.
- **Replay/idempotency**: redelivery before/after send and acknowledgement,
  timeout with completed external effect, same transaction ID/different intent,
  same intent/different key, key-window expiry, operator replay and stale-worker
  replay.
- **Lifecycle/deletion**: provider-created but uncommitted, locally purged but
  externally live, externally deleted but locally ready, tombstone before/after
  backup point, PITR/stale delta/rejoin resurrection, partial decommission,
  retained-owner entity and cleanup while reserved.
- **Revision**: zero/one/maximum candidates, N superseded by N+1 before and after
  download, coalesced gap, slow/dead acknowledgement, retained-old ceiling,
  maximum delta chain, forced full snapshot and continuous writer churn.
- **Selection**: uniform/weighted/round-robin, deterministic and random seed,
  population smaller/equal/larger than workers, records enter/leave, worker
  scale/restart, identical seeds, extreme weights, hotspot and starvation.
- **Identity/PKI**: bootstrap with unavailable time/CA, SAN/audience/subject/actor
  mismatch, old/new CA and JWKS overlap, partial rollout, expiry/revocation,
  rejected bearer-only input, mTLS or declared DPoP proof from the wrong key or
  endpoint, replayed/absent DPoP nonce, private-key mount in every non-owner
  service.
- **Resources**: shared Orchestrator CPU quota, heap allocation/retention, GC,
  threads and executor queues; Dataset versus control JDBC pool/permits, lock/WAL,
  Rabbit channels and broker flow/alarm; queue full, network bandwidth, disk
  low/full, volume missing/read-only;
  principal/environment/Dataset/deployment caps for MCP, proof, hydration,
  refresh, staging, audit and external entity creation.
- **Failure duration**: transient, retry-window, safe-horizon, RTO, prolonged,
  recovery with backlog, repeated flap.
- **Zoom out**: user workflow and support claim, shared infrastructure, current
  Redis behaviour, install/upgrade/rollback/backup/restore, operator error,
  asymmetric partition and stale-node rejoin, assessor/auditor evidence,
  signature/time authority, the untrusted MCP client
  boundary, combined Orchestrator/Dataset/Docker-control TCB and final-processor
  blast radius. Do not infer authority from client wording or MCP evidence, or
  infer platform lifecycle recovery from Dataset-state recovery.

## 9. Performance and non-interference experiment

The performance tool must not pass by hiding work or omitting stalls.

1. Freeze the complete section 19.6 manifest and acceptance policy before the
   run. Record highest passed release gate, topology, `SupplyRouteLease` policy,
   embedded Dataset-module build/configuration and common/SDK contract versions,
   the `Q-MVP-1K-24H` 50,000 eligible-record target and 55,000 maximum (or the
   exact different profile being claimed), record/projection bytes/TTL,
   sharing pattern, live-resize policy and convergence SLOs,
   refresh load, consumer validity requirements, selection/replay policy,
   external-entity limits, MCP/control-plane and qualified operator-UI read
   background load,
   resource limits, numeric success/error/latency/resource/evidence SLOs,
   analysis/power method, SUT double, versions, and host. Prove the capacity SUT
   double can sustain at least twice the highest offered load independently.
2. Freeze representative **and maximum qualified cardinality** before measuring:
   authorised and hidden Datasets, bindings/consumers per Dataset, operations,
   Fitness evaluations/assertions, proof facts, worker reports, snapshot rows/
   bytes, identifier/reason lengths, data skew/selectivity, pages and concurrent
   principals. Exercise empty, typical, maximum and adversarial-selectivity
   datasets. For every inventory/detail/tab query capture statement count and
   plan shape, actual versus estimated rows, rows read/returned, payload bytes,
   shared-buffer hit/read, temporary spill, sort/hash memory, lock/WAL pressure,
   JDBC pool wait/occupancy and server CPU/allocation. Run the UI contract's
   minimum 20 authenticated sessions at the minimum permitted refresh interval,
   including slow, hidden/offline and proof-reading clients. A fixed SQL count
   is an N+1 guard, not sufficient performance evidence; an unbounded scan,
   unstable plan, pool starvation or unqualified cardinality makes the result
   inconclusive even when latency happens to pass.
3. Estimate lab noise with repeated feature-disabled runs. If the proposed 1%
   non-inferiority bound is below observable noise, improve the lab or declare
   the result inconclusive; do not widen analysis after seeing results.
4. Run at least five independently started, alternating/randomised paired
   feature-disabled, enabled-idle, and active replicates with at least 15 minutes
   steady state at each decision load. `Q-MVP-1K-24H` uses exactly the frozen
   50,000 eligible-record target and at least two concurrent traffic swarms
   sharing the authority, and preserves each swarm's frozen non-zero load
   share. Each active replicate exercises one audited increase to 50,000 and
   one decrease under `STANDBY_TO_TARGET`, including restart at each policy/
   fill-cycle/application boundary. Use the same data projection, request, SUT
   behaviour, warm-up rule, instrumentation, and fault schedule. Treat the
   separate 24-hour active run as endurance, not a lone paired replicate. In
   active runs, execute the predeclared representative Orchestrator control and
   authorization probes so shared-process starvation is observable rather than
   inferred from traffic alone.
5. Generate open-loop/planned-start load. Record offered, scheduled, started,
   business-valid successes, failed, timed-out, unclassified, skipped, and
   schedule lag. Reconcile delivery attempts, unique offered transaction IDs,
   unique idempotency keys and unique external business effects. Only an
   expected, sink-validated outcome before the absolute deadline counts as
   achieved throughput; a duplicate effect is an error, never extra throughput.
6. Observe worker dispatch/service time separately from SUT response time, plus
   whole-Orchestrator and Dataset-attributed CPU, allocation/heap, GC pauses,
   threads, executor queues/rejections, JDBC waits, Rabbit channels/confirms,
   network, DB locks/WAL, existing WorkItem queues, and Orchestrator journal,
   authorization, health and control-operation latency. Capture selection
   distributions and maximum
   per-record/category concentration, validity-band eligibility and starvation,
   revision candidate/activation lag, active/requested policy versions,
   fill-cycle opening reason/state/accounting, target-change and due-schedule
   lag, trusted-time uncertainty, external entity growth/cleanup, route-lease
   issue/renew/reject/rebind, Dataset reconciliation,
   and MCP/proof/bulkhead rejection rates. Capture desired/actual Swarm
   Controller-runtime reconciliation only when that separate platform claim is
   being measured.
7. Apply the predeclared confidence bounds and safety invariants. Publish all
   runs, including failed and anomalous runs. Explain exclusions individually.
8. Find the repeated knee and apply the 70% headroom rule before advertising a
   maximum. A different host/topology/data profile creates a different claim.

Use independent paired runs or predeclared steady blocks as the statistical
unit and account for serial correlation. Do not manufacture narrow confidence
intervals by treating every request in one run as an independent replication.

The `dataset-qualified-core` two-swarm profile,
`Q-SENSITIVE-ENTERPRISE-v1`, every
`Q-DATA-REFRESH-<sourceProfileVersion>`, and 30-swarm shared/isolated claims are
separate experiments. A core result does not prove sensitive-profile non-
interference; reusable shared data results say nothing about consumable,
exclusive, ordered, or transaction-bound modes.

An enabled-active result is inconclusive if its configured selection mix is not
observed at the external sink, if a stricter consumer is silently starved, if
duplicate effects are unclassified, or if evidence/control load was omitted
from the frozen profile. Control/MCP abuse and revision-churn
experiments run both separately to find their limits and concurrently at the
admitted maximum to demonstrate that their bulkheads do not steal traffic-path
headroom. Because the Dataset module is in-process, a traffic pass is also
inconclusive if Orchestrator control/authorization probes, executor/pool waits,
heap/GC or health responsiveness exceed a frozen limit even when worker
throughput appears unaffected.

## 10. Automated check portfolio

Automation should free people to investigate, while preserving independent
oracles and fault realism.

| Layer | Required checks |
|---|---|
| Contract/static | Validate JSON/OpenAPI/AsyncAPI, the standalone `scenarios/managed-datasets/<id>/dataset.yaml` package and `DRAFT -> PUBLISHED -> RETIRED` commands, package-local Dataset Contract field subsets, executable scenario-local `datasets/requirements.yaml`, explicit per-scenario `datasetRef + datasetContractId` mapping, runtime-resolved binding, package source `assetPath`, adapter/profile/settings compatibility, Fitness, mapping, selection, snapshot, route-lease, cancellation, core-key and qualification-record contracts. Reject cross-Dataset contract references; Dataset definitions copied into scenario bundles; another scenario/bundle ID; missing/extra/duplicate/wildcard mapping; a selected contract missing a required scenario field; missing/unknown adapter settings; unsupported capabilities; and absolute, URI, drive-prefixed, backslash, traversal, out-of-root, missing, non-file or symlink-escaping assets. Prove only plan materialisation emits a container path with the frozen package/file digest. Enforce strict fields/types/ranges/null handling, 43-ID `DSUI` registry validity, inward dependencies, exact MCP catalogue including package operations, language/REST drift, closed reason codes, routing/redaction rules, supported dependencies, SBOM and secret hygiene |
| Unit/property/model | Trusted-time behavior across restart; Fitness and validity demand; capacity and supply ownership; cancellation/idempotency/provider-effect races; immutable generation, temporal state and tombstones; claim and route fencing; PostgreSQL/outbox/schedule recovery; revision/snapshot/auth activation; canonicalization and digests; selection distribution; canonical proof; encryption envelope, transactional nonce allocation, key rotation and qualification-record activation; separate desired-runtime ownership only for the platform recovery claim |
| Formal/model-based | Explore Dataset crash, reorder/loss, stale claim/lease/incarnation, cancellation around ambiguous provider effects, PostgreSQL/outbox recovery, key rotation, qualification activation, clock rollback, supersession, replay and stale-state/tombstone resurrection. A separate platform model covers desired/actual controller divergence; an isolated `Q-HA-RESTORE-v1` model covers its chosen external deletion authority. Every counterexample receives a regression scenario and documented assumption |
| Data/policy conformance | product mapper versus independent reference mapper for identity, relationships, classification, projection and batch accounting; product Fitness Contract result versus the independent evaluator over frozen authoritative evidence; product authorization versus independent access-matrix evaluator; mutation suite proves every oracle detects seeded defects |
| Component | Dataset-schema constraints, migrations, queries, claim/cancel concurrency, outbox relay and embedded-module restart reconciliation; common-contract compatibility; worker-SDK bounded hydration/atomic swap/Fitness receipt/safe horizon; manager-SDK DatasetGuard aggregation; Swarm Controller route leases; module-owned key/nonce update-before-use rotation, snapshot pagination, source commit/refresh, auth and provider cleanup adapters; signed qualification-record activation; desired-controller-runtime reconciliation only for the separate platform claim |
| Protocol/integration | Mandatory two-stage ensure-running/start-readiness-supply ordering; canonical `ph.control` correlation; current-incarnation readiness and route fencing; Rabbit confirms/acks/redelivery; `SupplyRouteLease` lifecycle and publish races; no inferred routing or topology fallback; immutable transaction/idempotency identity; TLS/workload identity and CA/JWKS rotation; MCP Protected Resource Metadata, RFC 8707 resource/audience validation, no token pass-through, token exchange, RFC 8705 mTLS sender constraint, declared DPoP ingress, revocation and separate stdio credentials; Orchestrator Dataset port-to-worker SDK scope; revision/auth activation; module liveness and controller lifecycle boundaries |
| Official-ingress E2E | Direct source path through the embedded module and current leased route to durable commit; authorised cancellation with conclusive/ambiguous/late outcomes; Fitness evaluation; hydration/auth/SUT use/proof; vars and admission; signed qualification-record activation; feature-scoped Orchestrator restart/reconciliation; key rotation; stricter shared validity; traffic replay; external decommission/tombstone; static revalidation; core rejection of sensitive admission; and Redis compatibility. Exact interrupted swarm lifecycle and restore remain separate suites |
| Operator UI | `CH-34`--`CH-40`; canonical response-to-DOM comparison for inventory, all five detail views, Swarm dependencies and real Runtime Inspector; every `DSUI` loading/current/background/stale/partial/reconciling/empty/denied/limited/unavailable/incompatible state; authorised totals/facets/cursors, no client decision inference, hostile identifiers, cache/storage identity changes, link/back/focus/keyboard/screen-reader/zoom/reflow and Firefox viewports; API/UI/MCP fact and denial convergence; adverse-state remediation and producer/cleanup ownership truth |
| Security/adversarial | Canaries; independent permission, enumeration, audience and actor decisions; zero-effect denial across cancel and generic workflow/swarm/MCP routes; qualification-record and canonicalization tamper/replay; proof-create permission/idempotency/quota abuse; malformed inputs; proxy/SSRF; PKI/mTLS/DPoP replay; shared process/container and Docker-control blast radius; sensitive-profile denial while Docker control is unmediated; ciphertext, AAD, key-manifest and secret faults; and separately scoped restore-profile attacks |
| Upgrade/recovery | Clean install in the existing Orchestrator container; common/SDK/module compatibility; expand/migrate/contract; interrupted migration; supported rollback/forward repair; downgrade rejection; routine key rotation without qualification-identity churn and pinned-contract change with requalification; repeated running-unhealthy/startup crash and feature reconciliation; asymmetric partitions; MVP stale-node/tombstone non-resurrection; separately, platform swarm-command recovery and restore-profile deletion-authority behavior |
| Performance/endurance | Paired shared-JVM non-interference, open-loop knee, traffic plus Orchestrator control/authorization probes, CPU/allocation/heap/GC/executor/JDBC/Rabbit isolation, selection realism, unique-effect reconciliation, validity starvation, revision churn, MCP status/proof bulkheads, cold-start/steady-state restart waves, refresh/cleanup maximum and 24-hour recovery trends; UI/API cardinalities with query plans, rows/bytes/spill/locks/pool waits, at least 20 authenticated sessions and browser render/task evidence |
| Evidence supply chain | `EVD-009` parent/child registry completeness and requirement-test graph plus `EVD-010` candidate qualification-record activation, manifest and artifact digest closure, independent-oracle reconciliation, detached signature, wrong-signer/post-signing mutation and clean-room verification |

Coverage is discussed by risk, state/transition, interface, data classification,
platform/profile, boundary, and acceptance ID. Code or case coverage alone is
not a release argument.

## 11. Session workflow, notes, and debrief

### 11.1 Before a session

Record:

- charter/risk/acceptance IDs and the question to answer;
- build, manifest, data/security profile, topology, and known state;
- whether the session targets feature-scoped Dataset durability, the deferred
  platform lifecycle-recovery claim, or both, with separate expected results;
- Orchestrator/Dataset shared-JVM limits and the combined security trust boundary
  relevant to the session;
- primary and independent oracles, controls, and fault permissions;
- timebox, stopping conditions, and safety/cleanup constraints.

### 11.2 During a session

Keep timestamped notes for setup, actions, observations, evidence references,
coverage/variations, anomalies, bugs, oracle disagreements, and new questions.
Distinguish observed fact from inference. Preserve failing seeds, request IDs,
fences, revisions, manifests, raw samples, and clocks needed to reproduce.

### 11.3 PROOF debrief

The tester and lead debrief every material session using:

- **Past**: what happened since the prior debrief and what changed in the model.
- **Results**: findings, evidence, coverage, requirements informed, and what was
  not observed. Report Dataset truth/reconciliation, running-worker continuity,
  Orchestrator non-interference and interrupted swarm-command recovery as
  separate observations; one must never stand in as evidence for another.
- **Obstacles**: blocked control/observation, environmental noise, oracle
  weakness, tooling defects, and unsafe conditions.
- **Outlook**: residual risks, hypotheses, next charter/check, owners, and
  decisions needed.
- **Feelings**: confidence, unease, surprise, and where intuition suggests a
  hidden problem. Treat this as a lead for investigation, not proof.

A useful debrief is a decision conversation, not a pass-count recital.
Every restart debrief ends with explicit supported wording and non-claims. In
particular, “Dataset state survived and reconciled” must not be shortened to
“PocketHive recovered” when create/config/start/stop/remove recovery was not
both in scope and observed. A sensitive-profile debrief also records whether
raw Docker control was removed or mediated and whether the combined
Orchestrator/Dataset trust boundary passed independent review.

## 12. Confidence grades

Confidence is per claim/profile and limited by the weakest critical evidence;
grades are not averaged.

| Grade | Evidence available |
|---|---|
| 0 — assertion | Narrative/design/case-study claim only; no implementation evidence |
| 1 — local | Review plus deterministic unit/static checks for the mechanism |
| 2 — component | Real component/dependency contract exercised with controlled doubles |
| 3 — nominal system | Official-ingress end-to-end flow on the reference deployment without adversarial faults |
| 4 — adversarial | Boundary, concurrency, fault, security, and recovery evidence cross-checked by an independent oracle |
| 5 — operational | Grade 4 plus production-like capacity/endurance, restart/rotation/backlog waves, repeated runs, and reproducible resource evidence on the exact manifest |

### 12.1 Decision gates: what “green” means

Green is always the `PASS` result of one named gate. It is never a colour copied
from a dashboard, the absence of comments, an average score or permission to
claim a later gate. `FAIL` identifies contradicted evidence; `INCONCLUSIVE`
identifies missing/weak control or observation. Neither can be changed to green
by accepting schedule risk.

| Gate | PASS requires | PASS permits | PASS does not permit |
|---|---|---|---|
| `G-DESIGN-READY-v1` | Lifecycle, assurance, UI specification, wireframe/README and the schema-valid 43-ID `DSUI` registry have zero known deterministic contract or safety-copy contradiction; canonical operation/proof/revision/supply semantics have one authority; every remaining product/architecture choice is visibly labelled as a decision with owner and due point; primary and adverse journeys can be reviewed; status and meeting brief say Grade 0 and list non-claims | Internal Product/Architecture/UX/QA/Operations/Security discovery and Three Amigos decisions | Architecture approval, implementation estimate/commitment, behavioural evidence, release or scale/security claim |
| `G-TEAM-APPROVED-v1` | `G-DESIGN-READY-v1` plus recorded Product, Architecture, Security, Operations, UX and QA decisions on the embedded-module boundary, core/deferred scope, owners, deployment constraints, first-use-case gate and explicit non-claims | Contract integration and implementation-readiness preparation | Implementation, release, capacity/security claim or waiver of unresolved decisions |
| `G-IMPLEMENTATION-READY-v1` (`M0`) | `G-TEAM-APPROVED-v1` plus approved standalone Dataset-package lifecycle/schema, package-local Dataset Contracts, deployment-scoped Dataset Space/registration lifecycle, Scenario Manager UI/API and governed MCP commands, PostgreSQL core adapter plus deferred Redis adapter contract, scenario-local `datasets/requirements.yaml`, per-scenario mappings, runtime-resolved bindings and package `assetPath` materialisation; ports/adapters and dependency direction are enforceable; the full `EVD-009` parent/child registry, applicability matrix, oracle ownership, model/TCK plan, supported dependency baseline, first real source/SUT profile and measurable outcome are closed; no unresolved P0 design blocker or dual authority remains | Estimation and implementation of the named M1 vertical slice through official ports/ingress | Release, production readiness, Redis adapter enablement, `dataset-qualified-core`, sensitive, HA or scale claim |
| `G-M1-INTERNAL-v1` | The exact M1 build passes deterministic component/TCK checks and the official-ingress vertical slice against independent database/outbox/Rabbit/worker/traffic/provider ledgers. The bounded M1 fault set injects before/after Dataset transaction commit, outbox publish/confirm, worker apply/activation, Orchestrator restart/reconciliation, claim/route fence validation, Trusted-Time loss/rollback, authorisation deny/revoke and provider-call ambiguous/late effect; each run proves the declared single durable/accounted outcome, zero effect for denied/stale work, and no secret/value leakage or request-time central Dataset I/O. All counterexamples, failures and limitations are retained, and the build labels itself `dataset-dev-synthetic` | Internal learning, demonstrations and retargeting charters | Customer/release readiness or extrapolation to operational capacity/security |
| `dataset-qualified-core` (`M2`) | Every applicable core parent/child assertion passes at its required grade, all six applicable loss themes are controlled, `EVD-009` is complete, no unexplained oracle disagreement remains, and the exact manifest passes the operational/non-interference profile below | Release of only the exact reusable `NON_SENSITIVE_SYNTHETIC` core profile | `SENSITIVE_TEST`, interrupted swarm-lifecycle recovery, HA/restore, 30-swarm, consumable/exclusive/ordered/transaction-bound or other unqualified claim |
| `Q-SENSITIVE-ENTERPRISE-v1` (`M3`) | The exact M2 build/profile also passes every non-waivable sensitive child, combined/extracted TCB decision, identity/key/egress/leakage/adversarial and signed-evidence gate at its required grade | Admission and support of `SENSITIVE_TEST` only on that exact qualified manifest | Any different topology/build/profile or deferred HA/scale/compliance claim |

The current design may pass `G-DESIGN-READY-v1` while team approval remains
`NOT_EVALUATED` and evidence remains Grade 0. That is an intentional green
outcome for a decision workshop, not approval or a product qualification
result. Gate results are recorded in a review report with the
spec commit, registry digest, reviewers, open decisions and exact permitted
wording.

### 12.2 Release confidence

Minimum release confidence is gate-specific.

For `dataset-qualified-core`:

- every applicable core functional criterion: Grade 3 or its higher risk-
  specific grade;
- scope, durable/idempotent accounting, claim and `SupplyRouteLease` fencing,
  trusted time, snapshot/final materialization, executable data semantics,
  exact-version Dataset fitness and fail-closed readiness,
  traffic replay accounting, MVP tombstone/stale-state non-resurrection,
  external decommission, static-record drift, embedded Dataset-module
  PostgreSQL/outbox/schedule restart reconciliation and MCP truth: Grade 4;
- `Q-MVP-1K-24H` at its exact 50,000 eligible-record target/55,000 maximum,
  including live target increase/decrease convergence, every admitted core
  `Q-DATA-REFRESH-*`, hot-path non-
  interference, bounded evidence load, refresh continuity and shared-
  infrastructure performance: Grade 5. The exact profile also demonstrates
  selection realism, validity fairness, unique external-effect reconciliation,
  fitness reevaluation before the earliest `safeUntil`, valid continuity or
  safe pause through outage/restart, revision/control bulkheads, shared
  Orchestrator JVM/executor/pool non-interference and bounded cumulative
  external entities;
- deployment/readiness/retention operations: at least Grade 3, raised by the
  applicable durability risk above; and Redis compatibility: Grade 4.

For `Q-SENSITIVE-ENTERPRISE-v1`, the exact build/deployment first satisfies the
core gate and additionally demonstrates leakage, authorization, egress,
identity/PKI/sender constraint, key-manifest/canonicalization integrity, host
artifact controls and TCB blast radius at Grade 4; every sensitive
throughput/refresh/non-interference claim is Grade 5 on the exact sensitive
manifest. A core pass is not security evidence. Exported enterprise evidence
authenticity is Grade 4 for signing/timestamping and cannot raise a weaker
underlying claim.

Because the bounded module shares the Orchestrator process/container and its
current Docker-control authority, the embedded deployment must reject
`SENSITIVE_TEST` unless that raw authority has been removed or mediated and the
combined TCB has passed the exact sensitive gate. Extraction into a separately
contained process is an alternative qualification topology, not part of the
core MVP. A logical package boundary or separate JDBC role/pool alone is not
security isolation evidence.

Each exact interrupted swarm-lifecycle recovery, 30-swarm, HA or restore claim
is separately qualified on its distinct profile; no such platform claim is
inherited from core Dataset restart evidence.

Repeated evidence may raise confidence; a contradiction, version/topology
change, unexplained anomaly, or newly exposed risk may lower it. “All automated
checks passed” has no inherent grade. Reviewed automated adversarial faults with
an adequate model and genuinely independent oracle can support Grade 4;
automation without those properties cannot.

A schema, mapping, selection-policy, source/provider contract, authorization
policy, canonicalization, identity/CA, cryptographic envelope, migration,
Dataset-module/worker-SDK protocol, common contract, major dependency, topology
or resource-limit change invalidates the affected evidence unless a reviewed
impact map shows that the
claim is unchanged. A mixed-version rollout and rollback path have their own
profile; a clean-install result cannot qualify them.

## 13. Release/claim review checklist

Before a release or new support claim, confirm:

- scope and security/data classification are explicit;
- `G-IMPLEMENTATION-READY-v1` passed for the implemented contract baseline and
  `R-SSOT-DRIFT` remains closed: canonical schemas, generated Java/TypeScript,
  OpenAPI/AsyncAPI, proof/operation values, snapshot semantics, UI copy and the
  acceptance registry agree with no compatibility fallback;
- `managed-test-data-ui-acceptance-registry.json` validates against its pinned
  schema, contains exactly the 43 current `DSUI` IDs once each under `FUN-014`,
  gives every child one accountable evidence owner and explicit design,
  implementation and release evidence stages, and has its digest present in
  the complete `EVD-009` run evidence index. A design `PASS` cannot populate or
  promote either later stage;
- `FUN-014` and every applicable `DSUI` child have linked evidence; production
  `ui-v2` uses only authorised canonical Orchestrator read models, contains no
  wireframe/test-fixture fallback, exposes no Dataset value or mutation control,
  implements every stale/partial/reconciling/denied/error/responsive state, and
  passes every applicable `CH-34`--`CH-40` charter, including the qualified UI
  polling non-interference workload and stranded-resource truth boundary;
- the MVP Dataset MCP surface registers exactly `dataset_status`,
  `dataset_source_operation_status`, and `dataset_prove`; its untrusted client
  receives no Dataset values, secrets, credentials, approval or authority;
  proof creation requires VIEW plus `dataset:proof:create`, request-bound
  idempotency and quotas; and
  the release makes no claim for additional read-only tools, write autonomy or
  general agent-governance programmes deferred in section 4.3;
- `CH-12` and `CH-24` prove that an unauthorized Dataset reference through
  existing generic workflow/swarm/MCP routes receives the same product-side
  authorization/admission denial as direct ingress, agrees with the independent
  `R-AUTHZ-ORACLE` evaluator, and causes zero durable Dataset mutation, dispatch
  or external effect;
- the release decision names `dataset-qualified-core`,
  `Q-SENSITIVE-ENTERPRISE-v1`, or an optional claim; UI/API/MCP reports no
  higher profile, and the core gate rejects `SENSITIVE_TEST` before any
  persistence, provisioning, hydration or send;
- all section 28 IDs applicable to that gate and every machine-readable child
  assertion link to immutable passing evidence; no unexecuted child, required
  row or applicable P0 control is waived, and `EVD-009` passes;
- all P0/P1 risks applicable to the named claim meet their minimum confidence.
  Deferred `R-RUNTIME-OWNER`/`R-CONTROLLER` exposure is recorded as an explicit
  platform non-claim, not accepted as though the wider recovery criterion had
  passed. A residual applicable P1/P2 disposition may document exposure beyond
  a passing row, but cannot change a failed or inconclusive criterion into pass;
- the release explicitly states that `swarm-plan-handoff-loss`,
  `control-plane-command-lifecycle-gap`, `R-RUNTIME-OWNER` and `R-CONTROLLER`
  remain platform limitations unless `Q-PLATFORM-RECOVERY-v1`, through
  `DUR-009`/`OPS-009`, has passed; their open status does not turn
  feature-scoped Dataset evidence into a failure or a wider recovery claim;
- every supply dispatch passes `FUN-012` using a current opaque Controller-
  issued `SupplyRouteLease`; no caller-supplied, inferred or fallback route
  exists, and rebind fences the prior topology/incarnation;
- every lifecycle change needed for supply is issued by the existing
  Orchestrator/Swarm Controller through canonical `ph.control`; a correlated
  successful outcome and fresh exact-incarnation `RUNNING`, input-ready and
  route-fence observation precede reservation/outbox publication. Start denial,
  timeout, stale observation, stop or rebind publishes no supply; a current
  already-running producer is handled as an idempotent ensure-running result;
- Swarm Controller alone creates and verifies the selected canonical WorkItem
  route capability. The release profile freezes and qualifies its durability,
  bounds, overflow, confirms, manual acknowledgement/redelivery,
  delivery-limit and dead-letter semantics; mismatched topology fails
  reconciliation before supply, with no adapter declaration, delete/recreate,
  silent downgrade, retry loop or fallback route;
- the Orchestrator-owned Dataset module in the existing container passes the
  feature-scoped children of `DUR-001`--`DUR-005`, `OPS-005` and `OPS-007`:
  repeated process/container restart preserves PostgreSQL truth and resumes
  claims, schedules and outbox intent without premature ready, stale dispatch
  or duplicate logical effect;
- only if `Q-PLATFORM-RECOVERY-v1` is advertised,
  Orchestrator's durable command/desired-runtime state is the sole owner of
  every dynamic Swarm Controller and `DUR-009`/`OPS-009` prove reconstruction of
  exact plan/config/workers/topology/leases before dispatch;
- dependency versions are supported, pinned, scanned, and identical to the run;
- executable Dataset/mapping/selection contracts and independent mapper/policy
  oracle versions are frozen, mutation-qualified, and included in the manifest;
- every required binding freezes the exact Dataset Fitness Contract reference
  and digest; every assertion passes against independently checked authoritative
  evidence; `FAIL` invalidates active use, while `UNKNOWN`, missing evidence or
  a superseded version blocks start/new activation and permits only the exact
  signed prior-`PASS` local view strictly before its `safeUntil`; the reported
  proof contains no Dataset values;
- the exact traffic replay profile is named; offered IDs, delivery attempts,
  idempotency keys and external business effects reconcile without an exactly-
  once implication for `AT_LEAST_ONCE`;
- trusted-time bootstrap, persisted floor and restart/rollback evidence pass;
  no worker can be ready on a stale or uncertain reference;
- every shared consumer's validity requirement is admitted into demand or
  rejected explicitly, with no hidden eligible-count starvation;
- `LIF-013` proves exactly one active Supply Policy/version/controller owns each
  inventory scope through takeover/restart, and reserved `REPLENISH` is rejected;
- snapshot manifests, candidate/retained revision bounds and churn convergence
  pass at the frozen maximum; no slow/dead member can cause unbounded growth;
- external-entity cumulative limits, drift validation, cleanup ownership,
  decommission and MVP stale-delta/local-projection/rejoin non-resurrection pass
  for every admitted source profile;
- if `Q-HA-RESTORE-v1` is advertised, its separate destructive restore proves
  the newest independently retained deletion manifest dominates the restored
  backup/PITR state before any record becomes selectable;
- the selected data distribution observed at the external sink is within the
  frozen tolerance and no unreported record/category hotspot exists;
- liveness-remediation ownership, running-unhealthy recovery, crash-loop
  backoff, asymmetric-partition and stale-node-rejoin game-day evidence exist;
- all supported install, migration, mixed-version, rollback or forward-repair
  paths pass `OPS-008`; unsupported downgrade/rollback fails explicitly before
  mutation;
- for `Q-SENSITIVE-ENTERPRISE-v1`, PKI bootstrap/renewal/revocation and partial
  rollout pass `SEC-012`; workload and MCP-downstream identities prove
  RFC 8705 mTLS binding, any declared DPoP ingress proves key/nonce/replay controls,
  bearer-only sensitive admission fails, and private-key ownership matches the
  TCB;
- for `Q-SENSITIVE-ENTERPRISE-v1`, the embedded profile is denied while the
  Orchestrator retains unmediated raw Docker control; admission requires that
  authority to be removed or mediated and the combined Orchestrator/Dataset
  process/container TCB to pass independent blast-radius review, or requires a
  separately qualified extracted deployment. Separate packages, DB roles,
  pools or executors alone do not satisfy this condition;
- for `Q-SENSITIVE-ENTERPRISE-v1`, key-manifest/nonce/key-transition and
  canonical prepared-request contracts pass `SEC-013`--`SEC-014` with
  independent security review and published vectors; final-processor TCB/co-
  tenancy/plaintext bounds and measured blast radius pass `SEC-015`;
- raw evidence, failed/anomalous runs, oracle disagreements, and limitations are
  present, not only summaries;
- shared-JVM CPU/allocation/heap/GC/thread/executor/JDBC/Rabbit measurements and
  representative Orchestrator control/authorization probe results pass the
  frozen `PER-001`--`PER-005`, `PER-008` and `PER-010` limits;
- UI/API qualification covers the frozen empty, representative, maximum and
  adversarial-selectivity cardinalities and records query-plan shape, estimated
  and actual rows, rows read/returned, bytes, spill, lock/WAL, JDBC pool wait/
  occupancy, server resources, browser render/tasks and at least 20 concurrent
  authenticated sessions; fixed SQL count alone is not accepted as a pass;
- backup/key/volume assumptions and destructive actions are stated;
- the advertised claim names its exact profile and does not generalise to
  untested allocation modes/topologies;
- operational alerts, runbooks, rollback/kill switch, owners, and residual risk
  acceptance exist;
- an exported enterprise evidence pack passes `EVD-008` with complete digest
  closure, detached signature, trusted timestamp, authorised signer/build
  identity and successful clean-room verification; local unsigned drafts are
  labelled non-authoritative.

The review outcome is one of `PASS`, `FAIL`, or `INCONCLUSIVE`. A conditional
pass is represented as a narrower named profile, not ambiguous prose.

## 14. Qualification report template

Every report contains:

```text
Claim and profile:
Release gate and highest passed profile:
Decision-gate results (`G-DESIGN-READY-v1`, `G-TEAM-APPROVED-v1`, `G-IMPLEMENTATION-READY-v1`, M1/M2/M3 as applicable):
Build/spec commit and image digests:
Host/deployment/dependency manifest:
Embedded Dataset module and common/worker/manager SDK contract versions:
Dataset/source/policy/security versions:
Dataset Fitness Contract reference/digest, evaluation result and `safeUntil`:
MCP evidence-client scope and cross-path authorization result:
SupplyRouteLease issuer/policy and frozen plan/topology revision:
Dataset PostgreSQL/outbox/schedule restart-reconciliation result:
Interrupted swarm-lifecycle recovery: NOT CLAIMED | separately qualified result:
Desired Swarm Controller-runtime owner/row/incarnation (only when claimed):
Shared-JVM non-interference and Orchestrator control-probe result:
UI/API cardinality, query-plan/pool and concurrent-session result:
Combined Orchestrator/Dataset/Docker-control trust-boundary decision:
Schema/mapping/selection/reference-oracle versions:
Traffic replay/idempotency and external-lifecycle profile:
Trusted-time/PKI/canonicalization profile:
Sensitive admission decision and evidence:
Risk, parent acceptance and child assertion IDs:
Loss-theme status, leading risk, owner and next control:
Acceptance-registry schema/version/count/digest and lint result:
Test story (charters, automated checks, coverage, faults):
Oracle story (product and independent evidence):
Raw evidence index and analysis version:
Evidence index digest, signer, timestamp authority and verification result:
Results and confidence grade per claim:
Bugs, anomalies, contradictions, exclusions:
Residual risks and owners:
Decision: PASS | FAIL | INCONCLUSIVE
Exact supported wording and explicit non-claims:
Reviewers and date:
```

The report is retained with enough configuration, seeds, time source, raw data,
and scripts for another qualified person to reproduce the conclusion. Sensitive
values are never included in the evidence pack.

The report also identifies which components hold sensitive local projections,
keys or final-send authority; the measured compromise blast radius; supported
install/migration/rollback paths; external cleanup owner; liveness-remediation
owner; SupplyRouteLease issuer and fence; Orchestrator desired-controller-
runtime recovery owner only if that platform claim is made; the embedded
Dataset-module recovery owner; common/SDK compatibility; and every assumption
inherited from the executable protocol model. An enterprise export is
authoritative only as the signed/timestamped immutable pack; a rendered summary
is a view of that pack, not a replacement.

## 15. Research and method boundary

The RST heuristics in this strategy guide investigation; they are not a
certification scheme. Architecture and case-study references are maintained in
specification section 33, including their limitations. When a source, product,
or dependency changes, re-check the exact claim and move affected confidence
back to unknown until proportionate evidence is repeated.
