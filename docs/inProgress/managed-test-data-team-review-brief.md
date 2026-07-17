# Managed Test Data Team Decision Brief

Status: internal decision workshop brief; not an implementation or release
approval

Normative sources:

- `managed-test-data-lifecycle-generic-spec.md`
- `managed-datasets-operator-ui-design-spec.md`
- `managed-test-data-assurance-strategy.md`

This brief summarizes decisions; it does not redefine API, event, persistence,
UI, proof, security, or acceptance contracts. If it conflicts with a normative
source, the normative source wins. After team approval, accepted architecture
decisions must move into PocketHive's canonical documents and machine schemas
before implementation.

## 1. Workshop outcome

The workshop determines whether PocketHive should invest in the M1 learning
slice, chooses a recommended direction where evidence permits, and assigns a
named owner and due date to every unresolved row in section 6. It need not
force a technical decision that lacks evidence. It does not approve M2,
estimate the complete capability, or create a capacity, resilience, security,
accessibility, or regulatory claim.

The idea is ready for the workshop when:

- deterministic contract drift is zero across the three normative companions
  and planning wireframes;
- every unresolved product or architecture choice has an accountable owner
  role and decision gate, with one named accountable person assigned by the
  workshop close rather than an implementation fallback;
- the M1 learning slice and M2 release target are visibly different;
- the wireframes label authoritative, applied, stale, unavailable, and
  use-specific decisions honestly; and
- the assurance strategy can trace every UI requirement to named risks,
  charters, independent oracles, and a confidence gate.

## 2. Problem and product hypothesis

Long-running performance and simulation workloads need safe test entities and
refreshable material that can outlive one scenario, be shared by multiple
swarms, and remain usable without central data access on the measured request
path. Today, the proposed capability is not implemented, so operator readiness,
proof, recovery, and performance remain Grade 0 design claims.

The hypothesis is that a Managed Dataset bounded context can reduce the time
and uncertainty involved in preparing safe reusable test data while preventing
invalid starts and preserving performance-test fidelity. The first-use-case
record in the lifecycle specification must freeze a real operator task, source
and SUT sandbox, provider limits, owners, baseline, and numeric target before
M1 is estimated or implemented as product work.

## 3. Recommended architecture direction

Use an embedded, independently bounded Managed Dataset module in Orchestrator
for the first delivery:

- Scenario Manager owns and persists authored versioned definitions, policies,
  binding definitions, schemas, declared uses, and permission/access-policy
  definitions. Orchestrator PostgreSQL owns only the admitted immutable runtime
  snapshots/digests, candidate/active activation pointer and runtime state;
  Orchestrator enforces runtime authorisation.
- The Managed Dataset application/domain boundary owns runtime Dataset truth,
  Fitness, supply operations, revision activation, proof composition, and
  feature-scoped Dataset reconciliation/recovery decisions.
- PostgreSQL is the sole positive business authority. An outbox records
  publication intent in the same transaction as domain state. Purpose-
  separated, non-rollbackable witnessed heads for core key state,
  qualification and restore deletion are independent **veto-only** safety
  authorities: they can keep a scope non-serving but cannot create a Dataset
  fact, grant access or supply key material.
- RabbitMQ keeps three explicit lanes. Every swarm template, plan, start, stop,
  remove, allowlisted live configuration, status request, outcome, status and
  alert uses the existing `ph.control` control plane. Typed `DATASET_SUPPLY`
  uses the canonical WorkItem plane; replaceable metadata-only revision hints
  use the Dataset hint plane. No Dataset queue is a second swarm control plane,
  and RabbitMQ is never Dataset authority.
- Worker adapters hydrate immutable local projections in the background and
  perform selection/materialization without central request-time I/O.
- UI and MCP are authorised read adapters. Administrative lifecycle commands
  use a separately authorised official Orchestrator command surface.
- Even the non-sensitive qualified core applies AES-256-GCM to record/material
  and operation-staging payloads. Keys live in owner-restricted Docker-secret
  files; update-before-use rotation retains required backup keys and is fenced
  by signed subject floors plus complete witnessed journals. The free reference
  requires a physical TPM 2.0 NV witness (not an emulator), or M0 must qualify
  an equivalent independently operated WORM/remote monotonic service.

The domain and application layers depend on small ports, not HTTP, RabbitMQ,
PostgreSQL/JPA, Docker, UI, MCP, scheduler, or Orchestrator infrastructure
types. Adapters with weaker semantics fail admission rather than invoking a
fallback. Extraction into a separate deployment remains possible behind the
same ports if an independently available or sensitive profile later requires
it.

### 3.1 Decisions from the team review

| Question | Closed design answer |
|---|---|
| Where do components and data sit? | Scenario Manager persists authored immutable definitions. The Managed Dataset module and durable lifecycle reconciler sit inside `orchestrator-service`; Orchestrator PostgreSQL stores admitted definition snapshots/digests, records, revisions, candidate/active policy state, fill cycles, schedules, operations and evidence. Swarm Controller owns WorkItem and Dataset-hint topology, route leases, the scenario timeline and readiness aggregation; existing components retain their current control-queue declaration pattern. Source swarms execute source work; traffic workers hold immutable local views and their traffic pacer. |
| Is existing 50,000-record use/add-back supported? | Legacy Redis pop and explicit push-back remain supported but are not atomic borrow/return and have no qualified 50,000-record claim. Existing CSV input is worker-local sequential/rotating state, restarts at row zero, and is neither a shared add-back path nor 50,000-record evidence. The Managed Dataset release profile targets 50,000 eligible reusable `SHARED` records across at least two swarms. Managed records remain locally reusable and therefore need no add-back. Consumable/exclusive/state-loop semantics remain deferred. |
| Can size change while swarms run? | The proposed feature activates an immutable audited Supply Policy version. A higher target opens one durable `TARGET_INCREASE` cycle only when it is above the prior target and current effective supply. A lower target holds new reservations, prepares a non-authoritative transition manifest, then commits one deterministic Fitness-preserving `READY -> STANDBY` revision; convergence completes only after that canonical revision is worker-applied. It changes desired eligible inventory, not physical row/external-entity count, and never silently deletes or rewires. Unsafe or infeasible values are rejected with zero effect. |
| How is work scheduled? | A PostgreSQL-backed Dataset lifecycle reconciler runs independently of scenarios. The existing controller-local scenario timeline schedules swarm steps, and the existing worker-local traffic pacer controls request rate. Dataset target is not `scheduler.maxMessages`, scenario duration or traffic rate. |

The M1 learning slice may use a smaller deterministic population to prove these
state transitions and fault boundaries. Only M2's exact 50,000-record/55,000-
maximum, two-swarm performance and endurance profile may establish the support
claim.

## 4. Delivery boundary

### M1 — learning slice

M1 proves that the chosen boundaries can work end to end. It shall include a
deterministic, non-sensitive provider/SUT double; one relationship-preserving
shared reusable Dataset scope; one source workflow; PostgreSQL/outbox recovery;
one selector projection; one final material projection; two consumer bindings
in two concurrent traffic swarms; worker-local activation; exact Fitness; and a
`DatasetProof/v1` result through `FLOW_PROVEN` for an identified transaction.

M1 is successful only when faults at transaction, publish, apply, restart,
fence, time, authorization, and external-effect boundaries converge without an
unsafe selection, duplicate uncontrolled effect, false readiness, hidden-scope
leak, or request-time central Dataset I/O. M1 is engineering evidence, not a
production capacity or business-value claim.

### M2 — releasable capability

M2 begins only after the first-use-case record is approved. It adds every
applicable MVP acceptance child, real source-profile qualification, supported
platform versions, production UI states, operational ownership, recovery and
cleanup evidence, performance/non-interference experiments, and a complete
run-specific evidence index. M2 cost/operations therefore include application-
level AES-GCM persistence, Docker-secret custody, key rotation/retention,
physical-or-equivalent monotonic-head provisioning, full witness journals,
capacity/endurance monitoring, reset/migration ceremony and coordinated
rollback recovery; these are not M3-only work. Sensitive profiles remain
disabled unless their additional gates pass independently.

The M2 capacity profile is fixed at 50,000 eligible reusable records,
`maximumReady: 55000`, at least two concurrent traffic swarms, live target-up
and target-down transitions, and the named 24-hour load/endurance gate. A
configured target or successful M1 demo is not capacity evidence.

## 5. Non-goals for this decision

- No general-purpose data platform or Redis replacement.
- No request-time central Dataset lookup.
- No consumable/exclusive/ordered allocation semantics in the reusable MVP.
- No autonomous MCP mutation or model-derived authority.
- No claim of exact interrupted whole-swarm command recovery.
- No PCI or other sensitive-profile admission from a core-profile result.
- No distributed database, multi-region, HSM, or extracted service without a
  measured requirement and separately approved profile.

## 6. Decisions required from the team

The due point for every row is before `G-IMPLEMENTATION-READY-v1`. The
workshop must replace each role placeholder with one accountable named person
and a calendar due date; until then the affected contract remains closed to
implementation.

| Decision | Recommended starting position | Accountable role; required approvers/consulted roles |
|---|---|---|
| First real operator task, source and SUT sandbox | Select one non-production, non-sensitive use case with two representative consumers and numeric outcomes | Product owner accountable; Dataset, SUT and source-adapter owners approve |
| M1 versus M2 | Treat M1 as a bounded learning slice and M2 as the release target | Product lead accountable; engineering lead approves |
| Deployment boundary | Embedded modular monolith with enforced hexagonal package/API boundaries | Architecture owner |
| Supported infrastructure baseline | Pin one supported RabbitMQ patch/image digest and freeze the shared PostgreSQL pool, role, connection and resource budgets in the M0 manifest | Platform owner accountable; architecture and release owners approve |
| Runtime reference | Stable logical Dataset reference; resolve infrastructure-specific names behind adapters | Architecture owner accountable; contract owner approves |
| Permission model | Server-side scope filtering before totals/facets; explicitly decide who may view declared-use Fitness and consumer facts | Product-security owner accountable; auth owner approves |
| Administrative controls | Separate authorised command API/CLI/runbook for pause, resume, breaker reset, exact `CancelSupplyOperation/v1` and decommission; UI/MCP remain read-only. Cancellation intent never claims provider cancellation without conclusive no-effect evidence | Operations owner accountable; security and source-adapter owners approve |
| Monotonic safety-head deployment | Free reference starts with three fixed physical TPM 2.0 NV counter/extend namespaces plus separate full subject chains and complete bounded journals; M0 may select a qualified WORM/remote equivalent only after identical canonical-vector, inclusion/anti-truncation, availability and free-profile evidence. Freeze the provider-neutral genesis/subject/journal/snapshot/receipt schemas, stable qualification lineage, post-initialisation C0/H0/Names/attributes, non-ORDERLY device/host policy, one writer/CAS owner, chain storage, torn-tail/half-transition recovery, reset/migration, write endurance/capacity, per-namespace blast radius and recovery owner | Security owner accountable; platform and architecture owners approve; operations consulted |
| Platform lifecycle limitation | Keep it outside the Dataset durability claim; approve detection, cleanup owner/SLO and explicit non-claim | Platform owner accountable; release owner approves |
| Canonical language | Dataset health, supply position, per-binding admission, running continuity and proof remain separate facts | Product owner accountable; UX and QA owners approve |
| Qualification ownership | Fund the independent oracles, conformance fixtures, evidence registry and performance lab as product work | QA lead accountable; engineering lead approves |

An undecided row blocks implementation of the affected contract. Teams must not
choose locally, infer a default, or add a compatibility adapter to defer the
decision.

## 7. Readiness labels for the workshop

| Claim | Workshop label |
|---|---|
| Problem and architecture direction are coherent enough to discuss | **PASS — `G-TEAM-REVIEW-v1`, Grade 0 internal workshop only** |
| UX flows are suitable for concept feedback | **PASS — Grade 0 concept feedback only** |
| Architecture and contracts are approved | Not yet; decisions in section 6 are open |
| Implementation may start | No; canonical contracts, schemas and M0 evidence are absent |
| Capability is qualified or releasable | No; current confidence is Grade 0 |

The facilitator must begin with these labels and close with named decisions,
owners, due dates, dissent, and residual risks. A positive reaction to the
wireframes is not architecture approval or qualification evidence.

## 8. `G-TEAM-REVIEW-v1` review record

Result: **PASS at confidence Grade 0 for an internal decision workshop only**
on 2026-07-17.

- Evaluated branch: `docs/managed-test-data-lifecycle-spec`.
- Evaluated HEAD: `7ab5fe358de24888f1e0fa9fdf2675cb42f2fa38` plus the
  uncommitted review remediation recorded by the source manifest below.
- Expected remote parent:
  `ebc8086b81989bc5660c9be4c2adf57f2ec7db48`.
- Evaluated working-tree source-manifest digest (normative companions,
  approval summaries, wireframe source/QA and acceptance registry):
  `sha256:0cb83606737cca6a41670927a65acd89afc0cec7ef56d06c36eac8bdecceea99`.
- The source manifest is the ordered `sha256sum` output for these twelve files,
  hashed once more from the repository root:
  `managed-test-data-lifecycle-generic-spec.md`,
  `managed-test-data-assurance-strategy.md`,
  `managed-datasets-operator-ui-design-spec.md`,
  `managed-datasets-team-design-overview.md`,
  `managed-test-data-stakeholder-one-page.md`, wireframe `README.md`,
  `design-qa.md`, `index.html`, `app.js`, `styles.css`, and the acceptance
  registry JSON and schema JSON. The exact reproducible command is recorded in
  section 10.
- Acceptance registry: 35 unique `DSUI` IDs; registry digest
  `sha256:2edf7ba3d047f00da5aa32f75afaea7b788257b83b30a98b1ffa2d8ec616a45a`;
  schema digest
  `sha256:c7979e51f8762d752edc805fdc40deafd607dc317ca28e68983906ecdbe4a754`;
  Draft 2020-12 AJV validation passed.
- Derived stakeholder DOCX digest:
  `sha256:76a8de8d2482775817ed33dfeebed293cf5ab4e4016f709f8575f008b6b398b7`.
  It was rendered
  after the neutral-language update and visually inspected as one clean page.
- Visual evidence boundary: the existing Firefox PNGs predate the current
  neutral fixtures and resize specimens and are reference-only. They are
  excluded from this gate manifest. Current-source visual fidelity,
  interaction behavior and accessibility conformance are not claimed.
- Deterministic checks passed: HTML validation, CSS validation, JavaScript
  syntax, Markdown/local-link and traceability lint, ID/ARIA integrity,
  closed-enum drift checks, schema validation and `git diff --check` against
  the frozen source manifest.
- Final verification completed at source level: agentic-AI boundary/truth
  review, independent senior architecture/SOLID/hexagonal review, QA/RST risk-
  and-oracle review, and independent UX semantics/responsive-source review.
  Human architecture, security, product, operations and release approvals are
  intentionally pending; a current rendered UX/accessibility review is also
  pending and is not a blocker to this Grade 0 concept-only gate.
- Open decisions: every row in section 6; each is an explicit blocker with one
  accountable role, required approvers and a due point before
  `G-IMPLEMENTATION-READY-v1`. The workshop must assign one named accountable
  person per row.

Permitted wording:

> The Managed Dataset concept is coherent and reviewable enough for an internal
> cross-functional decision workshop. It remains a Grade 0 design and is not
> approved for implementation or release.

Not permitted: “architecture approved,” “implementation ready,” “secure,”
“accessible,” “production ready,” “qualified,” “scalable,” or any statement
that generalises the fictional wireframe facts into runtime evidence.

## 9. Research anchors used in the review

These primary references informed the review; the PocketHive specifications
remain the requirement authority:

- Alistair Cockburn's
  [Hexagonal Architecture](https://alistair.cockburn.us/hexagonal-architecture/)
  for application-boundary use cases, ports and isolated adapter testing.
- AWS Prescriptive Guidance on the
  [transactional outbox](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html)
  for atomic domain-state/publication intent and idempotent duplicate handling.
- RabbitMQ's
  [publisher-confirm and consumer-acknowledgement guidance](https://www.rabbitmq.com/docs/confirms)
  for keeping broker acceptance independent from consumer application.
- W3C WAI's [Tabs Pattern](https://www.w3.org/WAI/ARIA/apg/patterns/tabs/)
  and [WCAG 2.2 resize-text guidance](https://www.w3.org/WAI/WCAG22/Understanding/resize-text.html)
  for tab semantics, keyboard behavior and 200% review.
- Rapid Software Testing's
  [methodology](https://rapid-software-testing.com/what-are-the-foundations-of-rst/),
  the [testing-versus-checking distinction](https://developsense.com/blog/2009/08/testing-vs-checking),
  and the
  [Heuristic Test Strategy Model](https://www.satisfice.com/download/heuristic-test-strategy-model)
  for separating deterministic conformance checks from skilled investigation
  and for risk-, state-, boundary- and oracle-led coverage.
- [JSON Schema Draft 2020-12](https://json-schema.org/draft/2020-12)
  for the machine acceptance-registry validation contract.
- NIST
  [SP 800-188](https://csrc.nist.gov/pubs/sp/800/188/final) for choosing an
  explicit data-sharing model and treating de-identification, synthetic data,
  protected query access and controlled enclaves as different risk-bearing
  strategies rather than assuming that masking creates safe test data.
- NIST [AI RMF 1.0](https://doi.org/10.6028/NIST.AI.100-1) and the
  [Generative AI Profile](https://doi.org/10.6028/NIST.AI.600-1) for explicit
  human roles, oversight, tracking, documentation and non-inflated AI claims.
- The versioned MCP
  [HTTP authorization specification](https://modelcontextprotocol.io/specification/2025-06-18/basic/authorization)
  and current
  [authorization security considerations](https://modelcontextprotocol.io/specification/draft/basic/authorization/security-considerations)
  for Protected Resource Metadata, resource indicators, audience validation,
  transport-specific credential handling and the prohibition on token
  passthrough.
- Google's
  [Chubby lock-service paper](https://research.google/pubs/the-chubby-lock-service-for-loosely-coupled-distributed-systems/)
  for treating coarse-grained leases and low-volume coordination as authority
  control rather than a high-throughput data path.
- Trusted Computing Group's TPM 2.0 Library
  [Part 1 Architecture v184](https://trustedcomputinggroup.org/wp-content/uploads/Trusted-Platform-Module-2.0-Library-Part-1-Version-184_pub.pdf)
  and
  [Part 2 Structures v184](https://trustedcomputinggroup.org/wp-content/uploads/Trusted-Platform-Module-2.0-Library-Part-2-Version-184_pub.pdf)
  for NV state, startup/shutdown and `TPMA_NV_ORDERLY` semantics; these are why
  the reference rejects reset-cleared or orderly-only state as a durable
  monotonic safety witness.
- OWASP's
  [Top 10 for Agentic Applications 2026](https://genai.owasp.org/resource/owasp-top-10-for-agentic-applications-for-2026/)
  as a current threat-review prompt for untrusted agent planning, tool use and
  cross-system action; PocketHive still fails closed by keeping MCP read-only
  and product-side authorization authoritative.

## 10. Integrity-manifest reproduction

Run from the repository root. The file order is part of the source-manifest
contract:

```sh
sha256sum \
  docs/inProgress/managed-test-data-lifecycle-generic-spec.md \
  docs/inProgress/managed-test-data-assurance-strategy.md \
  docs/inProgress/managed-datasets-operator-ui-design-spec.md \
  docs/inProgress/managed-datasets-team-design-overview.md \
  docs/inProgress/managed-test-data-stakeholder-one-page.md \
  docs/inProgress/managed-datasets-wireframes/README.md \
  docs/inProgress/managed-datasets-wireframes/design-qa.md \
  docs/inProgress/managed-datasets-wireframes/index.html \
  docs/inProgress/managed-datasets-wireframes/app.js \
  docs/inProgress/managed-datasets-wireframes/styles.css \
  docs/inProgress/managed-test-data-ui-acceptance-registry.json \
  docs/inProgress/managed-test-data-ui-acceptance-registry.schema.json \
  | sha256sum
```

The earlier capture-set digest may be reproduced for historical comparison,
but it is intentionally excluded from the current source-level gate because
those images predate the neutral fixtures and resize specimens:

```sh
find docs/inProgress/managed-datasets-wireframes/captures/spec-aligned \
  -maxdepth 1 -type f -name '*.png' -print0 \
  | LC_ALL=C sort -z \
  | xargs -0 sha256sum \
  | sha256sum
```
