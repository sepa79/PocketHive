# PocketHive MCP Java Migration and QA-Led Authoring Specification

## Status

`PROPOSED / TODO`

This is the implementation specification for replacing the Node.js PocketHive
MCP server with one Java 21 service. It is not evidence that the migration has
been delivered.

## Decision required

Approve a minimal Java migration of the existing PocketHive MCP with these
deliberate boundary changes:

1. Git remains the single source of truth for editable Scenario Bundle source
   and version history.
2. Scenario Manager continues to validate bundles and store only the current
   deployed bundle. Its implementation is not changed by this work.
3. The MCP provides agent-facing tools, connected skills, PocketHive context,
   bounded durable QA workflow state, and governed publication coordination.
   Owner-query and runtime-lifecycle tools remain application-stateless and use
   the existing PocketHive owners for current state. Direct publication remains
   independently callable but may use the bounded ticket and receipt state
   defined below. The MCP does not become a source repository, general workflow
   platform, or knowledge database.
4. The Scenario Bundle wizard becomes a QA-lead skill over one workflow engine.
   It must ask the user for requirements and must not infer them.
5. HiveMind is not a dependency of the MCP. The service must install, start,
   and provide its full contract when HiveMind is absent.
6. One Java MCP image is deployed locally by `build-hive.sh` or remotely by
   HiveForge. Clients connect to `https://<environment>/mcp` through the
   existing PocketHive public ingress, which transparently proxies the pinned
   MCP transport to the Java service.
7. The first release pins MCP `2025-11-25` and the official Java SDK version
   proved by Phase 0. MCP `2026-07-28` is a future, explicit protocol migration,
   not a negotiated fallback.

The main accepted trade-off is that Scenario Manager replace remains
last-write-wins because it has no atomic expected-version precondition. Git
provides history and rollback, but cannot make a concurrent replace atomic.

The state boundary deliberately accepts one MCP replica while the first release
uses its file store. This preserves server-enforced elicitation-response
provenance, restart recovery, cross-client resume, optimistic concurrency, and
single-use upload controls without adding a database or changing a PocketHive
service.

Remote authentication has one blocking prerequisite. The implemented
`docs/architecture/AUTH_SERVICE_API_SPEC.md` owns opaque PocketHive user and
service-principal tokens; it is not an MCP OAuth 2.1 authorization-server
contract. Phase 0 must approve a separate, contract-first Auth Service extension
that keeps Auth Service as the identity/grant single source of truth and adds the
MCP-required authorization flow, resource indicators, scopes, metadata, and
pre-registered client contract. This specification does not treat the current
opaque-token API as standards-compliant OAuth or silently introduce another
identity authority.

## Outcome

A novice can describe the test they need without knowing PocketHive. A QA
engineer can answer ordinary testing questions without knowing the Scenario
Bundle format. The connected agent can then:

- learn PocketHive terminology, architecture, capabilities, constraints, and
  tool usage from the MCP;
- discover and call retained deployment, swarm lifecycle, configuration,
  diagnostics, evidence, and cleanup tools independently of the wizard;
- keep multiple independent Scenario Bundle workflows in one agent session;
- ask for every applicable QA requirement and expose unresolved decisions;
- deterministically generate files in the user's active Git repository;
- preserve the complete committed mixed-file bundle, including shell, SQL,
  YAML/YML, JSON, CSV, and Markdown assets, without executing them;
- validate an exact bundle artifact through Scenario Manager;
- publish that exact artifact with an explicit `CREATE` or `REPLACE` decision;
- operate and diagnose PocketHive only through supported owner APIs; and
- produce traceable evidence tying deployment to client-asserted repository,
  commit, and path metadata, plus the verified uploaded archive digest and
  canonical bundle-content digest.

## Hard rules

1. Java 21 is the only server implementation language after cutover.
2. The Java service replaces `tools/pockethive-mcp`. Node and Java servers must
   not remain parallel supported products.
3. Keep the migration mechanical where current behaviour is valid. A behaviour
   change requires a rule, boundary, security, or stated product goal in this
   specification.
4. Apply PocketHive NFF: no inferred configuration, URL discovery, default
   localhost, adapter switching, mode fallback, create/replace fallback, or
   silent retry of an ambiguous write.
5. Git is the only authority for editable Scenario Bundle source and old
   versions.
6. Scenario Manager is the authority for bundle validation and the current
   deployed Scenario Bundle catalogue.
7. Orchestrator is the authority for swarm lifecycle, live configuration,
   runtime diagnostics, cleanup, and runtime evidence.
8. The MCP must not call RabbitMQ, Docker, Redis, ClickHouse, Grafana, WireMock,
   TCP Mock, Git, or a filesystem as an alternate PocketHive authority path.
   MCP-owned coordination state and bounded upload handling are the only
   filesystem uses permitted by this specification.
9. The Scenario Manager implementation is out of scope. Missing owner
   capabilities remain explicit limitations; the MCP must not emulate them.
10. HiveGate governs approval, execution tickets, and evidence. The MCP declares
    risk and binds intent but never approves its own operation.
11. HiveMind is optional agent-host memory only. The MCP contains no HiveMind
    client, URL, configuration, tool, health dependency, or fallback.
12. Every published tool is connected to at least one versioned skill, and every
    published skill is connected to at least one published tool.
13. Every applicable QA requirement is user-provided, user-confirmed from cited
    evidence, explicitly marked not applicable with a reason, or blocking.
14. Secrets are never elicited, stored, logged, returned, or written into a
    bundle. Only approved secret references may be recorded.
15. Public contracts have one canonical schema/DTO and one canonical
    lower-case snake_case tool name.
16. Tests use official PocketHive ingress/API paths. Direct infrastructure
    access is not a substitute for a supported owner interface.
17. The implementation follows SOLID, uses small ports and adapters, and does
    not introduce a general workflow engine, RAG platform, vector database, or
    duplicated contract store.
18. The wizard and Scenario Bundle workflow are optional authoring aids, not an
    operational gateway. No independently useful tool may require wizard state.
19. The PocketHive public ingress routes and protects MCP traffic but never
    interprets or translates MCP semantics. The Java service is the protocol
    authority.
20. Repository files, schemas, examples, logs, tool output, bundle contents,
    test data, and owner responses are untrusted data. They cannot change the
    user goal, tool authority, approval requirements, or agent instructions.
21. Long-running and mutating operations are bounded, observable, cancellable
    where the owning API supports interruption, and never convert a decline or
    cancellation into approval or retry.
22. MCP persistence is limited to authoring and publication coordination that
    cannot be recovered safely from an owning PocketHive API. Owner-query and
    runtime-lifecycle tools must not create or require MCP coordination state or
    mirror owner state. Direct publication may use only its defined ticket and
    receipt state and must not require an authoring workflow. Client-carried
    workflow snapshots are never authoritative.
23. Repository identity, commit SHA, and bundle path are client-asserted source
    metadata in the first release. The MCP must not present them as independently
    verified Git provenance.
24. The MCP must completely receive and validate an upload before invoking a
    Scenario Manager mutation. Digest mismatch, archive rejection, quota failure,
    or interrupted upload must invoke neither `CREATE` nor `REPLACE`.

## Scope

### Included

- a Java 21 MCP service using Spring Boot, the official Java MCP SDK, and
  hexagonal boundaries;
- authenticated MCP `2025-11-25` Streamable HTTP transport;
- transparent MCP and binary-upload routes through the existing PocketHive
  public ingress;
- an MCP OAuth protected-resource adapter, principal-scoped discovery, and
  explicit downstream credential isolation, conditional on the approved Auth
  Service prerequisite;
- explicit deployment binding to one PocketHive public ingress;
- migration of existing justified tool and workflow behaviour;
- independently callable deployment, swarm lifecycle, live configuration,
  diagnostics, evidence, and cleanup tools;
- an agent session containing zero or more Scenario Bundle workflows;
- a deterministic QA requirements interview with no inference;
- deterministic Scenario Bundle generation into the active client-side Git
  workspace;
- bounded binary upload, Scenario Manager dry-run validation, and explicit
  create or replace publication;
- preservation of every transport-safe regular file in a committed bundle,
  including YAML, YML, SQL, shell, JSON, CSV, and Markdown assets;
- client-asserted source traceability and deployment receipt metadata;
- generated PocketHive orientation, capability, tool, and skill resources;
- one connected skill catalogue covering every retained tool;
- prompt-injection and untrusted-content controls;
- correlated OpenTelemetry telemetry and explicit operation oversight;
- a thin VS Code MCP HTTP client with locally persisted connection profiles;
- local and HiveForge deployment of the same image; and
- unit, property, integration, acceptance, security, mutation, agentic, and
  Rapid Software Testing evaluation.

### Excluded

- changes to Scenario Manager, Orchestrator, or their persistence models;
- implementation of the separately approved Auth Service OAuth extension; it is
  a blocking prerequisite work package, not hidden MCP scope;
- an MCP-owned Scenario Bundle repository or version store;
- server-side Git access, repository checkout, directory scanning, file editing,
  or bundle-root configuration;
- durable persistence, backup, or retention of uploaded ZIP archives;
- MCP dependence on HiveMind or any other project-memory product;
- PostgreSQL, a new distributed workflow platform, RAG, embeddings, or vector
  search;
- client-carried authoritative workflow snapshots, signed continuation
  capsules, or client-specific workflow-state protocols;
- direct infrastructure administration;
- client-specific stdio transport after cutover;
- MCP protocol translation or revision fallback in Nginx or the Java service;
- MCP `2026-07-28`, Multi Round-Trip Requests, and the MCP Tasks extension in
  the first release;
- dynamic remote skill download, executable skill hooks, or skill scripts;
- hidden backward-compatibility aliases or config fallbacks;
- retaining unsupported tools solely to reach tool-count parity; and
- changing the VS Code extension away from its supported TypeScript host.

## Current and target boundaries

| Concern | Current | Target |
|---|---|---|
| MCP runtime | Node.js server | One Java 21 Streamable HTTP service |
| Bundle source | Server-configured local bundle roots | Active client-side Git repository |
| Bundle history | Git when used by the team | Git, required and explicit |
| Deployed bundle | Scenario Manager | Scenario Manager, unchanged |
| Bundle upload | Server reads a local directory | Client streams exact committed ZIP through a bounded MCP upload ticket |
| Workflow state | JSON beneath a bundle-root-dependent location | MCP-owned atomic file store on a dedicated persistent volume |
| Wizard | Separate wizard behaviour that can infer or default | QA-lead skill over the canonical workflow tools |
| Owner-query and runtime-lifecycle tools | Mixed tool-specific behaviour | Application-stateless calls to the owning API; no authoring-session prerequisite |
| Agent context | Assumes repository knowledge | Generated MCP resources and connected skills |
| Environment selection | Server/IDE PocketHive endpoint settings | One immutable endpoint per MCP instance; VS Code stores MCP connection profiles |
| Public transport | Direct stdio or service-specific connection | One authenticated `/mcp` route through PocketHive public ingress |
| Protocol ownership | Runtime-specific | Java MCP validates and implements pinned `2025-11-25`; ingress is transparent |
| Project memory | Optional external tooling | Optional external tooling; never an MCP dependency |
| Runtime operations | Includes direct infrastructure paths | Only documented Scenario Manager or Orchestrator APIs |

## Authority model

| Information or action | Authority | MCP role |
|---|---|---|
| Editable bundle files and history | Git repository | Generate a proposed file set and accept client-asserted source metadata plus a file manifest |
| Bundle format and validation | Scenario Manager authoring contract and validation API | Fetch, pin, compile, and submit |
| Current deployed bundle catalogue | Scenario Manager | Present a projection to clients |
| Swarm/runtime operations | Orchestrator | Typed adapter to documented APIs |
| QA intent and applicability | User | Ask, record disposition, and show unresolved topics |
| QA interview progress and answer provenance | MCP workflow store | Persist bounded coordination state; never replace the user's authority over intent |
| Tool contract | MCP `ToolDescriptor` catalogue | Generate registration, docs, skills links, and coverage |
| Static PocketHive architecture/contracts | Canonical repository documents | Publish generated, traceable projections |
| Live deployed capabilities | Owning PocketHive APIs | Fetch current data; fail if unavailable or stale |
| Approval and execution governance | HiveGate | Supply exact operation intent and consume governed result |
| MCP routing, TLS, and edge limits | PocketHive public ingress | No semantic interpretation; Java revalidates every request |
| User identity, grants, and MCP token issuance | Auth Service and its approved MCP OAuth extension | No duplicate user/grant store in MCP |
| MCP authentication and authorisation | Java MCP protected resource using the Auth Service contract | Authenticate, scope discovery/calls, and isolate downstream credentials |
| Optional project memory | Agent host/HiveMind | No role and no dependency |

Generated MCP resources are projections, not competing authorities. Every
static projection records its canonical source path, source anchor, build
revision, and digest. Every live projection records its owner and observation
time. A missing or stale authoritative source causes an explicit failure; it
does not fall back to embedded guesses.

~~~mermaid
flowchart LR
    U[User] <--> A[Agent and connected skills]
    A <--> N[PocketHive public ingress]
    N <--> M[Java MCP]
    A --> G[(Active Git repository)]
    M --> SM[Scenario Manager API]
    M --> O[Orchestrator API]
    HG[HiveGate] -. governs mutations .-> M
    HF[HiveForge or build-hive.sh] -. deploys same image .-> M
    HM[Optional HiveMind] -. agent-host memory only .-> A
~~~

## Architecture

Use a small hexagonal service. Domain and application code depend on narrow
ports; Spring, MCP protocol, HTTP clients, auth, file persistence, and upload
streaming stay in adapters.

| Component | Single responsibility | Primary ports |
|---|---|---|
| `AgentSessionService` | Own session lifecycle and workflow membership | `AgentSessionRepository` |
| `ScenarioWorkflowService` | Enforce workflow transitions and optimistic revision checks | `ScenarioWorkflowRepository` |
| `RequirementsInterviewService` | Select the next unresolved QA topic and apply user answers | `McpInteractionPort` |
| `CapabilityService` | Pin and compare current authoring capabilities | `AuthoringContractPort` |
| `BundleCompiler` | Deterministically produce a bounded bundle file set | None beyond accepted inputs |
| `BundleValidationService` | Validate an exact archive and record the canonical content digest | `BundleValidationPort`, `UploadTicketPort` |
| `BundlePublicationService` | Publish the approved exact archive using explicit mode | `BundlePublicationPort`, `UploadTicketPort` |
| `RuntimeToolService` | Delegate supported operations without creating authoring state | Narrow Orchestrator/Scenario Manager ports |
| `OperationService` | Coordinate only justified MCP-owned work that spans calls | `OperationRepository`, narrow owner ports |
| `ToolCatalogue` | Own descriptors and prove tool-to-skill coverage | `SkillResourcePort` |
| `KnowledgeProjectionService` | Serve traceable static and live MCP resources | Generated resources and owner API ports |

Do not introduce a generic domain HTTP client, generic repository, generic
workflow engine, or condition tree. New owner APIs extend adapters without
adding transport or infrastructure branches to domain logic.

### State boundary

The Java MCP is deliberately state-minimal rather than fully
application-stateless. Durable MCP state exists only where it preserves a
product guarantee that an owning PocketHive service cannot provide:

| Concern | State rule |
|---|---|
| QA interview | Persist the principal-bound session, workflow, authenticated elicitation-response provenance, state, and revision so elicitation, resume, and optimistic concurrency remain enforceable |
| Bundle upload | Persist only short-lived ticket and attempt metadata. A bounded quarantined spool may hold one in-flight archive until pre-owner validation completes; archive bytes are never durable state and are deleted deterministically |
| MCP-owned multi-call operation | Persist the minimum coordination metadata only when no single owner API owns the whole operation |
| Owner operation | Use the owner's operation reference and current status; do not mirror its state in MCP |
| Owner-query or runtime-lifecycle call | Authenticate and authorise each call, query the owner, return the result, and create no authoring session or workflow |
| Client | May cache opaque IDs and revisions, but is not the authority for workflow answers, transitions, provenance, tickets, or operation state |

This boundary keeps novice pause/resume, multiple workflows, evidence that an
authenticated conforming client returned an elicitation response, stale-write
rejection, and single-use publication controls. It does not prove that a human
personally authored a response. Moving the workflow into a client-provided
snapshot would require every MCP client to own recovery and concurrency, and
would let faulty or hostile clients forge response provenance. Signing or
encrypting such snapshots would add a second token and key lifecycle. Both
designs are out of scope.

An independently useful tool never reads or writes `AgentSession` or
`ScenarioWorkflow` merely to perform its operation. If an owning API returns an
operation ID, status and cancellation tools query that owner with the ID. A
local `OperationHandle` is permitted only for work that the MCP itself must
coordinate across calls and that the migration ledger explicitly justifies.

### Deployment configuration

One MCP instance binds to exactly one PocketHive environment.

Required production configuration:

- `PH_MCP_POCKETHIVE_INGRESS`: one validated public PocketHive ingress URI;
- `PH_MCP_PROTOCOL_REVISION=2025-11-25`: the only accepted first-release MCP
  revision;
- `PH_MCP_STATE_MODE=FILE`: explicit persistence mode;
- `PH_MCP_STATE_PATH`: dedicated persistent-volume path;
- `PH_MCP_UPLOAD_SPOOL_PATH`: dedicated quarantined temporary-upload path;
- `PH_MCP_OPEN_SESSION_TTL`: explicit ISO-8601 duration;
- `PH_MCP_CLOSED_SESSION_RETENTION`: explicit ISO-8601 duration;
- `PH_MCP_ATTEMPT_RETENTION`: explicit ISO-8601 duration;
- `PH_MCP_RECEIPT_RETENTION`: explicit ISO-8601 duration;
- `PH_MCP_MAX_OPEN_SESSIONS`: positive integer for the whole instance;
- `PH_MCP_MAX_OPEN_SESSIONS_PER_PRINCIPAL`: positive integer;
- `PH_MCP_MAX_WORKFLOWS_PER_SESSION`: positive integer;
- `PH_MCP_MAX_STATE_BYTES`: positive byte limit for all MCP coordination state;
- `PH_MCP_MAX_CONCURRENT_UPLOADS_PER_PRINCIPAL`: positive integer;
- `PH_MCP_MAX_CONCURRENT_UPLOADS`: positive integer for the whole instance;
- `PH_MCP_MAX_UPLOAD_BYTES`: positive compressed byte limit for one archive;
- `PH_MCP_MAX_UPLOAD_SPOOL_BYTES`: positive byte limit for the whole spool;
- `PH_MCP_MAX_ARCHIVE_FILES`: positive regular-file count limit;
- `PH_MCP_MAX_ARCHIVE_EXPANDED_BYTES`: positive expanded byte limit;
- `PH_MCP_MAX_ARCHIVE_NESTING`: non-negative nesting limit;
- `PH_MCP_MAX_ARCHIVE_COMPRESSION_RATIO`: positive ratio limit; and
- explicit authentication and authorisation configuration defined by the
  PocketHive security contract.

`PH_MCP_STATE_MODE=MEMORY` is permitted only where the deployment explicitly
selects disposable state, such as focused tests. It is never a fallback.
Missing, contradictory, or invalid configuration fails startup.

Run one service replica per environment while the file store is used. Scaling
beyond one replica requires a separate approved persistence design and is not
part of this migration.

### Public ingress and protocol contract

The first release implements only MCP `2025-11-25` through an explicitly pinned
official Java SDK. Phase 0 must record the exact SDK version, protocol schema
digest, supported client matrix, and conformance results. Unsupported or missing
protocol revisions fail explicitly. Do not hand-code a partial newer revision,
run dual protocol runtimes, or negotiate down to another revision.

`McpInteractionPort` keeps the application workflow independent of protocol
mechanics. Its `2025-11-25` adapter uses MCP elicitation. A future
`2026-07-28` adapter may use Multi Round-Trip Requests only after the official
Java SDK and every supported client pass the approved conformance matrix. This
port is not permission to support both adapters in one deployment.

The existing PocketHive public ingress, currently the UI Nginx container, owns
TLS termination and stable edge routing:

- `/mcp` proxies the exact `2025-11-25` Streamable HTTP endpoint, including
  required `POST` and `GET`/SSE behaviour;
- `/mcp/uploads/{ticketId}` streams a ticket-bound archive to the Java service;
- authentication, `Origin`, `Accept`, `Last-Event-ID`,
  `MCP-Protocol-Version`, correlation, and trace headers are preserved;
- request buffering, response buffering, body-size limits, rate limits, and
  streaming timeouts are explicitly configured for the selected routes; and
- external clients and acceptance tests use this public ingress, never the
  Java container port.

Nginx must not translate elicitation to Multi Round-Trip Requests, stateful to
stateless operation, `initialize` to `server/discover`, request or result
schemas, tool names, or protocol revisions. Edge checks do not replace Java
authentication, authorisation, origin, header, body, ticket, or schema
validation.

An MCP transport session is not an `AgentSession` and is never authentication.
When the selected Java SDK assigns `MCP-Session-Id`, the value is
cryptographically random and bound to the verified principal and OAuth client
ID. Every protected request is authenticated and authorised independently. A
required missing session ID fails HTTP `400`; a terminated or unknown session
ID fails HTTP `404`. Phase 0 explicitly chooses whether client `DELETE`
termination is supported. An invalid supplied `Origin` fails HTTP `403`; there
is no permissive origin fallback.

The external contract authorities are the official
[MCP `2025-11-25` specification](https://modelcontextprotocol.io/specification/2025-11-25),
[Streamable HTTP contract](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports),
[authorisation contract](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization),
and [Java SDK release](https://github.com/modelcontextprotocol/java-sdk/releases).
PocketHive records their pinned versions and digests; it does not copy or fork
their schemas into a second authority.

## Domain model

The terms below are proposed until this specification is approved.

### Agent Session — PROPOSED

An `AgentSession` is a principal-bound work container, not an MCP transport
session. It has an ID, owner principal, `OPEN | CLOSED | EXPIRED` state,
creation, expiry, close, and last-update timestamps, and a revision. One session
owns zero or more independent `ScenarioWorkflow` records.

Closing or expiry prevents creation or mutation of its workflows. Expiry is
deterministic from the configured TTL and never renews implicitly. Starting a
new session does not copy state from an expired session. Close and expiry do not
delete retained audit metadata or deployment receipts; configured retention
then removes them predictably.

### Scenario Workflow — PROPOSED

A `ScenarioWorkflow` produces one Scenario Bundle. It records its parent agent
session, principal, requirement dispositions, pinned capability fingerprint,
bundle file manifest, validation receipt, publication intent, deployment
receipt, state, and revision.

~~~text
DISCOVERING -> REVIEW_REQUIRED -> READY_TO_GENERATE -> GENERATED
     |                                  |                 |
     +---------------> BLOCKED <--------+-----------------+
GENERATED -> VALIDATED -> PUBLISHED
     |             |
     +-> BLOCKED <-+
Any non-terminal state -> CANCELLED
~~~

Every mutation supplies the expected workflow revision. A stale write fails
`WORKFLOW_VERSION_CONFLICT`. There is no automatic recovery from `BLOCKED` and
no implicit transition after restart.

Downstream evidence is valid only for the exact accepted requirements and
capability fingerprint that produced it:

| Event | Required transition and invalidation |
|---|---|
| An accepted requirement changes before publication | Increment the revision, invalidate generated files, validation, publication intent, and unconsumed tickets, then move to `REVIEW_REQUIRED`; use `BLOCKED` when the new value is unresolved or unsupported |
| The capability fingerprint changes before publication | Invalidate the same downstream evidence and move to `REVIEW_REQUIRED`; use `BLOCKED` when reconciliation finds unsupported intent |
| The cause of `BLOCKED` is explicitly resolved | Re-evaluate all applicable requirements and capabilities, then move to `REVIEW_REQUIRED`; never recover automatically |
| A change is requested after `PUBLISHED` | Keep the published workflow immutable and create a new workflow with a fresh review; never rewrite its receipt |
| `CANCELLED` | Remain terminal; resume requires a new workflow |

Generation, validation, and publication recheck the current workflow revision
and capability fingerprint. Stale downstream evidence fails
`WORKFLOW_EVIDENCE_STALE`; it is never reused or silently regenerated.

### Requirement disposition — PROPOSED

Each applicable requirement topic has exactly one disposition:

- `USER_PROVIDED`: the user supplied the value;
- `USER_CONFIRMED_SOURCE`: the user confirmed a value extracted from a named,
  hashed source;
- `NOT_APPLICABLE`: the user explicitly rejected applicability and supplied a
  reason;
- `UNKNOWN`: unresolved and blocking; or
- `DERIVED`: a deterministic mechanical result with recorded rule and accepted
  inputs.

`DERIVED` must not decide test intent, risk, applicability, SLAs, load, data,
security, setup, teardown, or an expected result. `AGENT_INFERRED` and implicit
defaults are forbidden.

### Answer Provenance — PROPOSED

`AnswerProvenance` records the verified principal key, verified OAuth client ID,
declared MCP client name/version, workflow and question IDs, requested-schema
digest, elicitation action, accepted-content digest when present, and
observation time. The principal key is the verified `(issuer, subject)` pair;
username, display name, and email are never identity keys. Client identity is
recorded separately and does not replace the principal.

The action is exactly `ACCEPT`, `DECLINE`, or `CANCEL`. Only `ACCEPT` with
server-validated content may create `USER_PROVIDED` or
`USER_CONFIRMED_SOURCE`. `DECLINE` and `CANCEL` leave the requirement `UNKNOWN`
and blocking unless the user separately and explicitly supplies the reason for
`NOT_APPLICABLE`. Response evidence proves only what the authenticated client
returned; it does not prove human authorship.

Required QA-intent elicitation uses the flat form schema supported by the pinned
MCP revision, omits `default` values, requests no credentials, and is validated
again by the server. The service rejects unexpected fields, invalid content, and
answers for a different workflow revision or principal.

### Bundle File Manifest — PROPOSED

A `BundleFileManifest` is the sorted list of every regular file in the uploaded
bundle. Each entry contains the normalised relative path, byte count, and
SHA-256 file digest. It includes supporting files regardless of extension and
excludes directories, ZIP timestamps, ownership, and POSIX mode because they are
not part of Scenario Manager's canonical content digest.

The client supplies the manifest with its source metadata. The MCP independently
recomputes it from the quarantined archive and rejects any missing, additional,
renamed, or changed file before an owner call. The manifest proves the uploaded
file set and bytes; it does not prove that client-asserted Git metadata is true.

### Tool Descriptor — PROPOSED

`ToolDescriptor` is the canonical definition of one published tool:

- tool ID and input/output schema references;
- handler binding;
- title, short agent-facing description, when-to-use, and when-not-to-use text;
- read-only/destructive/idempotent annotations;
- side-effect, risk, and bounded timeout classes;
- owner API;
- required scopes, authentication, and approval requirements;
- result-size, pagination, and resource-link policy;
- deployment eligibility and exact static unavailability reason;
- canonical failure codes and exact safe next-action tool IDs; and
- connected skill resource IDs.

Registration, human documentation, policy metadata, and coverage tests are
generated from this descriptor. Do not duplicate tool schemas or skill links in
separate handwritten catalogues. Tool annotations and next actions are advisory
metadata: they cannot grant authority, change HiveGate policy, or cause an
automatic call. HiveGate consumes a deployment-approved, digest-pinned
descriptor manifest from the immutable image; an unknown descriptor or digest
mismatch fails explicitly.

The canonical schema library defines one small structured result core:
`status`, `summary`, `correlationId`, optional `operationId`, typed `data`,
bounded `evidenceRefs`, exact advisory `nextActions`, and bounded `warnings`.
New or ledger-approved changed tools compose that core with one tool-specific
data schema. A `MIGRATED` tool keeps its characterised output contract; the Java
adapter must not add an unapproved wrapper. Large evidence is returned by an
authorised, expiring resource link instead of being placed in model context.

### Bundle Upload Ticket — PROPOSED

A `BundleUploadTicket` is short-lived, principal-bound, single-use, and bound to
a maximum size, content type, purpose, expiry, workflow, correlation ID,
client-asserted source metadata, and expected `BundleFileManifest`. A publication
ticket is additionally bound to the validated archive digest, canonical
bundle-content digest, publication mode, target scenario ID when replacing, and
governed operation intent.

A ticket ID is a non-secret opaque handle. The upload URI contains no credential
or query token and requires normal MCP resource-server authentication. A ticket
grants only the named upload. It is not an approval record.

### Publication Attempt — PROPOSED

A `PublicationAttempt` is the minimal crash-recovery record for one consumed
publication ticket. Its transitions are:

| Current state | Allowed next state |
|---|---|
| `PREPARED` | `RECEIVING` |
| `RECEIVING` | `VERIFIED` or `FAILED` |
| `VERIFIED` | `OWNER_CALL_IN_FLIGHT` or `FAILED` |
| `OWNER_CALL_IN_FLIGHT` | `SUCCEEDED`, `FAILED`, or `AMBIGUOUS` |
| `AMBIGUOUS` | `SUCCEEDED` only when the owning read contract proves the exact result; otherwise remain `AMBIGUOUS` |
| `SUCCEEDED`, `FAILED` | Terminal; a new attempt requires a new explicit ticket |

The service persists `RECEIVING` before accepting the first archive byte and
consumes the ticket at that boundary. It persists `VERIFIED` only after the
complete archive passes all bounds and digest checks. It persists
`OWNER_CALL_IN_FLIGHT` before invoking Scenario Manager. A definitive owner
response produces `SUCCEEDED` with a receipt or `FAILED` with a typed error. A
lost response, process death, or timeout after the owner call begins produces
`AMBIGUOUS` after restart or reconciliation; it never causes an automatic owner
retry.

Reconciliation uses only a read operation defined by the owning Scenario
Manager contract and records its observation. If that read proves the exact
scenario ID and canonical bundle digest, the attempt may transition to
`SUCCEEDED` with a reconciliation-marked receipt. If it cannot, the attempt
remains `AMBIGUOUS` and requires explicit operator resolution. Owner mutation is
never replayed. Orphaned spool bytes are deleted after their attempt state is
recovered; attempt metadata remains for its configured retention period.

### Operation Handle — PROPOSED

An `OperationHandle` is a principal-bound, explicit ID for bounded work that
cannot reliably complete within one short tool call. It records the tool,
target, intent digest, state, progress, timeout class, owner-operation reference,
correlation ID, timestamps, and revision. It is distinct from an MCP transport
session and an `AgentSession`.

An owner-issued operation ID remains authoritative when one exists; the MCP
does not copy its state into an `OperationHandle`. An `OperationHandle` is used
only when the MCP owns justified coordination spanning multiple calls. Such
work uses explicit start, status, resume, and cancel tools. Cancellation is
idempotent and stops further MCP work promptly. It does not claim rollback of
an owner action that already completed. If the owner cannot interrupt an
operation, the result states that limitation and switches to reconciliation;
it never reports cancellation as rollback.

### Deployment Receipt — PROPOSED

A `DeploymentReceipt` records:

- Scenario Manager scenario ID;
- repository identity;
- commit SHA;
- bundle path;
- source-metadata assurance, fixed to `CLIENT_ASSERTED` in the first release;
- `bundleFileManifestDigest` and regular-file count;
- uploaded ZIP SHA-256 `archiveDigest`;
- Scenario Manager canonical `bundleContentDigest`;
- target MCP environment ID;
- explicit `CREATE` or `REPLACE` mode;
- owning API response reference;
- principal, correlation ID, and timestamp.

The MCP validates the source fields' shape and binds them to the exact
publication intent and digests. It cannot verify that the repository, commit, or
path exists because it has no Git access. The receipt is traceability evidence,
not verified Git provenance or the authority for source, deployment state, or
approval.

### MCP Connection Profile — PROPOSED

A VS Code `McpConnectionProfile` contains an ID, display name, MCP URL,
authentication mode, and secret reference. Non-secret profiles use VS Code
`globalState` without settings sync, active selection uses `workspaceState`,
and credentials use `SecretStorage`.

This is distinct from a PocketHive SUT environment and from Scenario Bundle
environment configuration.

## QA-led Scenario Bundle workflow

The Scenario Bundle Wizard is a connected skill, not a second server-side
state machine. It instructs the agent to use the canonical session and workflow
tools, present plain-language questions, explain PocketHive concepts only when
needed, and wait for user answers.

The interview must cover and explicitly dispose of at least:

1. goal, risks, scope, and out-of-scope behaviour;
2. SUTs, endpoints, ownership, protocols, and environment constraints;
3. user journeys, example tests, schemas, contracts, and expected outcomes;
4. SLAs, SLOs, thresholds, error budgets, and stopping criteria;
5. load profiles, concurrency, arrival model, duration, ramping, and traffic
   shape that inform the Scenario Plan and moderator;
6. test-data strategy, storage, profiles, sources, volumes, lifecycle,
   uniqueness, privacy, retention, Redis datasets, CSV use, and cleanup;
7. authentication profiles and secret references;
8. setup, teardown, reset, seeding, and dependency requirements;
9. required background traffic and isolation from foreground traffic;
10. test oracles, negative cases, observability, diagnostics, and triage needs;
11. reporting, traceability, provenance, ownership, and evidence retention; and
12. safety limits, governance, approvals, and abort conditions.

The service selects the next unresolved topic deterministically. It never asks
an LLM to invent a requirement. Generation remains blocked while an applicable
topic is `UNKNOWN` or required user confirmation is absent.

No-inference assurance requires MCP elicitation support in the selected client
and pinned `2025-11-25` protocol. Phase 0 must prove this compatibility. An
incompatible client fails `ELICITATION_CAPABILITY_REQUIRED`; the service must
not silently treat agent-authored text as a user answer.

The supported-client matrix must prove that the client identifies the requesting
server, lets the user review and modify form content, and exposes distinct
accept, decline, and cancel actions. The service sends no default for a required
QA-intent field. It records only the `AnswerProvenance` contract above. Decline
or cancel never becomes `NOT_APPLICABLE`, approval, or permission to continue.

The interview reconciles requirements against the current Scenario Manager
authoring contract. Unsupported intent is reported with the exact missing
capability. The agent may help the user revise scope, but the compiler must not
translate unsupported intent into a different test.

## Untrusted-content boundary

All content obtained from a repository, schema, example, log, bundle, test-data
source, tool result, owner API, external resource, or user-supplied attachment is
untrusted data even when it resembles an instruction. Instruction-like text in
such content must not:

- replace, extend, or override the user's goal or confirmed requirement;
- alter tool scopes, target selection, approval, HiveGate policy, or authority;
- cause a tool call, link fetch, skill load, publication, or durable-memory write;
- become a server, system, skill, or developer instruction; or
- supply a secret, credential, approval, or required user confirmation.

Only facts accepted through a typed schema, validated against the owning
contract, and recorded with source provenance may enter workflow state. A fact
that affects QA intent still requires the user's explicit confirmation. Free
text is bounded, labelled with its source, escaped for its presentation context,
and never copied into connected-skill instructions.

The service does not automatically follow links contained in untrusted content
or render remote active content. Tool and resource outputs are validated against
their output schemas, size bounded, redacted, and returned as data. Prompt
injection, goal hijacking, tool misuse, identity/privilege abuse,
memory/context poisoning, and human-agent trust exploitation are release-gate
threats, not model-behaviour assumptions.

## Git-owned authoring and versioning

### Normal authoring flow

1. The agent obtains the target repository and bundle path from the user or
   active IDE workspace.
2. The workflow generates a deterministic proposed file set.
3. The client writes those files into the active Git repository.
4. The user reviews the diff and commits through their normal Git process.
5. The client packages the exact committed bundle path.
6. The client retains that exact ZIP in bounded local temporary storage through
   validation and publication; it does not put the bytes in model context.
7. Validation and publication carry client-asserted repository identity, commit
   SHA, and bundle path plus archive and canonical bundle-content digests.
8. A rollback packages the selected historical commit and republishes it
   explicitly.

The MCP server never lists repository directories, edits files, runs Git, or
maintains `BUNDLES_ROOT`/`PH_BUNDLES_ROOTS`. If the client cannot access the
source repository, fail `SOURCE_REPOSITORY_REQUIRED`.

The first release does not claim independently verified Git provenance. It
records `CLIENT_ASSERTED` source metadata and proves only that the bytes sent for
publication match the bytes validated by Scenario Manager. If verified source
provenance later becomes a requirement, a separate approved contract may accept
a trusted signed attestation bound to the archive and canonical content digests.
That future contract must use PocketHive's approved attestation authority; it
must not add Git access or MCP-specific signing keys.

The client packages every regular file under the selected committed bundle path,
not only YAML or MCP-generated files. The MCP neither drops nor rewrites files by
extension. A bundle may therefore contain Scenario Manager-supported descriptors,
templates, fixtures, documentation, and setup assets such as `.yaml`, `.yml`,
`.json`, `.csv`, `.sql`, `.sh`, and `.md`. The bundle file manifest records
each relative path and byte digest. The MCP treats these files as opaque data and
never executes scripts, SQL, Compose files, or other bundle content.

Validation and publication upload the same retained ZIP bytes. The client
deletes its temporary ZIP after success, cancellation, ticket expiry, or a
terminal failure. If those bytes are lost or the committed path changes, the
client must create a new archive and repeat validation; it cannot reuse the old
validation receipt or rely on byte-equivalent repackaging.

A historical bundle is obtained without changing the worktree, for example by
the user's Git-capable client using `git archive <commit> <bundlePath>`. Git
history is therefore available only when the repository is available to the
agent or client. The MCP does not add an implicit Git service.

### Storage and bloat

Scenario Manager continues to store only the current deployed copy. Replacing a
bundle overwrites that current copy, and its temporary upload storage is
cleaned by its existing behaviour. Old versions stay in Git and rollback is a
new explicit upload of an old commit.

The new MCP and VS Code flows must not use Scenario Manager's file-editing
endpoints as an authoring path. An out-of-band edit to the deployed copy is
deployment drift, not a new source version. Detect it by validating the current
Scenario Manager bundle and comparing its canonical content digest with the
latest deployment receipt. Reconcile by committing the intended source change
to Git and publishing it explicitly; never write the deployed copy back into
Git automatically.

This design does not add Scenario Manager filesystem version bloat. Large,
frequently changing CSV or binary datasets can still bloat Git. Bundles should
prefer small representative fixtures plus versioned external data references,
Redis dataset profiles, checksums, or Git LFS where team policy permits.
Generated CI artifacts must have an explicit retention cap.

## Validation and publication

A remote MCP cannot read the client's Git checkout, and ZIP bytes must not be
placed in model context or base64 tool arguments. Use a narrow two-phase upload
through the same public ingress as MCP.

Every upload first enters a bounded quarantine at `PH_MCP_UPLOAD_SPOOL_PATH`.
The service uses an opaque generated filename, owner-only permissions, no
executable access, bounded memory, and the configured per-principal,
concurrency, archive, and total-spool limits. It fully receives the archive,
computes its digest, and validates its type and entries before any owner call.
It rejects traversal, absolute paths, duplicate or colliding decoded names,
symlinks, hard links, device entries, excessive nesting, excessive file count,
and compressed, expanded, or compression-ratio limit breaches. A rejected,
interrupted, or quota-blocked upload invokes no Scenario Manager mutation.
There is no MCP file-extension allow-list inside a ZIP: after transport-safety
checks, the complete regular-file set is passed unchanged to the canonical
Scenario Manager validator. Scenario Manager remains the authority for bundle
layout, file semantics, and runnability.

The quarantine is temporary transport handling, not durable MCP state. Startup
recovery deletes orphaned archive bytes after reading their durable attempt
metadata. Successful and failed requests delete bytes deterministically. The
service never serves quarantined content, places it in a backup, or copies it
into model context. These controls follow the
[OWASP File Upload guidance](https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html)
without creating a second bundle store.

Before implementing the Java adapter, the owning
`docs/scenarios/SCENARIO_MANAGER_BUNDLE_REST.md` contract must canonically
define the validation, create, and replace endpoints used below, preservation of
regular-file paths and bytes, and its explicit POSIX-mode behaviour. This is a
contract-document correction only: it does not authorise Scenario Manager code
or persistence changes. Java ports, schemas, fixtures, and tests reference that
owner contract and must not redefine it as an MCP-owned API.

### Validation phase

1. The caller supplies client-asserted source metadata and the expected
   `BundleFileManifest`; a validation-prepare MCP tool binds them into a
   short-lived validation upload ticket. The tool mutates only bounded MCP
   coordination state and makes no Scenario Manager call.
2. The client streams a ZIP with authenticated binary HTTP `PUT` to
   `/mcp/uploads/{ticketId}` through the public ingress.
3. The MCP receives the complete ZIP into quarantine, enforces all upload and
   archive bounds, recomputes and compares the `BundleFileManifest`, and computes
   an `archiveDigest` SHA-256 over the exact ZIP bytes.
4. Only after step 3 succeeds, the MCP sends those immutable spool bytes to
   Scenario Manager `POST /validation/scenario-bundles`.
5. The MCP returns a validation receipt containing both the `archiveDigest`
   and Scenario Manager's canonical `bundleContentDigest` from
   `validation.artifactDigest`. The latter is calculated over sorted relative
   paths and file bytes and is the authoritative bundle validation digest.
6. The MCP deletes the quarantined bytes after the request.

### Publication phase

1. The caller explicitly supplies `CREATE` or `REPLACE`, validation receipt,
   the same client-asserted source metadata and `BundleFileManifest`, expected
   archive and bundle-content digests, and target scenario ID when replacing.
2. The governed publication-prepare tool binds that exact intent and returns a
   single-use publication upload ticket only after the required HiveGate path
   permits execution.
3. The client streams the identical ZIP again. The MCP persists the publication
   attempt state and receives the complete ZIP into quarantine.
4. The MCP enforces all archive bounds, recomputes and compares the
   `BundleFileManifest` and `archiveDigest`, and rejects any mismatch before an
   owner mutation begins. Matching ZIP bytes prove only that publication
   contains the bundle already validated by Scenario Manager; the two digest
   algorithms are never treated as interchangeable.
5. After persisting `OWNER_CALL_IN_FLIGHT`, it sends the verified immutable
   spool bytes only to:
   - `POST /scenarios/bundles` for `CREATE`; or
   - `PUT /scenarios/{id}/bundle` for `REPLACE`.
6. It persists the definitive result or `AMBIGUOUS`, returns the applicable
   receipt or typed error, then deletes all quarantined archive bytes.

`CREATE` and `REPLACE` never fall back to one another. An invalid target, digest,
ticket, source metadata record, or owner response fails explicitly. If the
client cannot perform binary ticket uploads, fail
`CLIENT_CAPABILITY_REQUIRED`; there is no inline/base64 fallback.

Scenario Manager currently offers no atomic expected-version condition for
replace. A preflight read followed by `PUT` would not close that race and must
not be presented as protection. Therefore:

- replace remains an explicit, governed, last-write-wins operation;
- an ambiguous replace result is not retried automatically;
- the response tells the caller to reconcile through Scenario Manager; and
- environments requiring create-only immutability can deny `REPLACE` through
  policy without changing MCP semantics.

## Tool migration and compatibility

Create a migration ledger for every current Node registration. Each entry has
exactly one disposition:

- `MIGRATED`;
- `REPLACED_BY <canonical_tool_id>`;
- `REMOVED_WITH_REASON`; or
- `BLOCKED_BY_MISSING_OWNER_API`.

For `MIGRATED` tools, retain the canonical underscore name and current schema
unless this specification records a necessary contract change. Preserve
observable behaviour with characterisation tests before deleting Node code.

Required boundary dispositions:

- remove bundle-root, server filesystem, environment-switching, and
  server-local bundle editing tools;
- remove `context_*` tools replaced by MCP resources;
- remove `env_*` tools replaced by deployment binding and client-side
  connection profiles;
- remove `workflow_hivemind_enrich` because project memory is an agent-host
  concern;
- replace duplicate `wizard_*` workflow state with the QA-lead skill over
  canonical workflow tools;
- retain Scenario Manager and Orchestrator tools only when backed by their
  official API; and
- mark direct RabbitMQ, Docker, Grafana, WireMock, TCP Mock, or filesystem tools
  blocked or removed until an owning service exposes the required API.

Tool-count parity is not a goal. Behavioural traceability is mandatory. Dotted
legacy names and runtime name modes are removed at atomic cutover unless a
future contract explicitly authorises compatibility.

### Independent tool use

The MCP tool catalogue is the primary capability surface. Every retained
capability is a first-class tool returned by MCP tool discovery, not a private
workflow subroutine. The wizard is a connected QA-authoring skill, and a
workflow is an optional coordinator for a multi-step outcome. Neither wraps
tools in a way that prevents direct use.

An authorised agent may independently call any retained tool for:

- Scenario Manager catalogue, validation, and publication;
- swarm creation, readiness, start, stop, inspection, and removal;
- live component configuration where the owner API supports it;
- runtime status, worker logs and versions, topology, timelines, and manifest
  diagnostics;
- reporting and evidence retrieval; and
- governed cleanup planning and execution.

Each independent call has a complete `ToolDescriptor`: explicit input,
preconditions, owner, side effects, authorisation, approval requirement,
result, and typed failures. It applies the same security, governance,
correlation, idempotency, and no-fallback rules as the coordinated workflow.
A tool may require an owner-defined precondition, validated artifact,
client-asserted source metadata, or prior resource ID. It must never require a
wizard session merely because the wizard can call it.

Every mutating call first exposes its exact environment, owner, target, impact,
intent digest, approval requirement, and whether owner-side rollback exists.
The agent must present that preview before requesting execution. User decline,
approval expiry, and cancellation are normal typed outcomes; they are never
retried, interpreted as approval, or hidden behind a different tool.

Each operation has a finite timeout class. Work that cannot finish within the
short-call bound uses owner-specific start, status, resume, and cancel tools.
These tools use the owner-issued operation reference where available. They use
an `OperationHandle` only when the MCP itself owns justified multi-call
coordination; they never mirror an owner operation into MCP state. Do not create
a generic operation router or block a request with polling sleeps. Progress is
monotonic and includes observation time. Cancellation is idempotent. Swarm or
load execution retains one explicit, authoritative stop/abort tool backed by
the owning API. MCP Tasks are not added until the pinned SDK and client matrix
explicitly support that extension.

In PocketHive terms, a Scenario Bundle is deployed and a swarm is created and
started. Agent guidance may explain this sequence when a user says “start the
scenario”, but it must use the existing owner operations and must not invent a
second lifecycle or silently execute inferred steps.

Connected skills cover both direct use and safe composition. They identify the
smallest appropriate tool, when a sequence is required, and when the QA-led
authoring workflow adds value. An agent debugging an existing swarm, for
example, does not start or reconstruct a Scenario Bundle workflow.

The proposed session family is intentionally small:
`agent_session_create`, `agent_session_get`, `agent_session_list_workflows`,
and `agent_session_close`. Existing workflow tools take the required
`agentSessionId` rather than creating another parallel workflow API.

## Connected skills and portable PocketHive knowledge

Every retained tool is linked from its `ToolDescriptor` to one or more
versioned Agent Skills-compatible resources:

`pockethive://skills/{skillId}/{version}/SKILL.md`

A skill may cover a coherent tool family; one unique skill per tool is not
required. The initial families are:

- PocketHive orientation and safety;
- QA no-inference interview;
- Scenario Bundle workflow authoring;
- Git bundle versioning, validation, and publication;
- SUT and endpoint modelling;
- authentication profiles and secret references;
- test-data, Redis dataset, and CSV strategy;
- load profile, traffic shape, Scenario Plan, and moderator;
- Scenario Manager validation and catalogue;
- swarm lifecycle and live configuration;
- runtime diagnostics and topology;
- virtualisation and supported fault simulation;
- reporting, traceability, and evidence; and
- governed cleanup.

Skills use progressive disclosure. Tool and skill discovery publishes only the
skill ID, name, description, version, content digest, URI, activation guidance,
and compatibility metadata. The client reads `SKILL.md` only when the current
request matches that guidance and reads referenced material only when needed.
It caches by version and digest and does not re-read unchanged content in the
same agent context. A simple direct call remains understandable from its
`ToolDescriptor`; reading a skill is not a mandatory tax on every call.

Skills contain procedures, question wording, decision checkpoints, tool order,
failure handling, and links to canonical resources. Their frontmatter, name,
description, compatibility, references, digest, and bounded size are validated
at build time. They do not contain secrets, hidden defaults, copied authority
data, approval decisions, executable remote hooks, or scripts. Skills and
static resources are build-time projections of canonical documents and include
the source and build digests; the runtime never downloads new instructions.

The following MCP resources let an agent work correctly in a repository with
no PocketHive files:

- `pockethive://knowledge/overview`;
- `pockethive://knowledge/glossary`;
- `pockethive://capabilities/current`;
- `pockethive://tools/catalogue`; and
- `pockethive://skills/catalogue`.

Static content is generated at build time from canonical PocketHive documents,
including `docs/ARCHITECTURE.md`,
`docs/architecture/workerCapabilities.md`,
`docs/ORCHESTRATOR-REST.md`, and relevant contract schemas. Live capabilities
come from Scenario Manager and Orchestrator owner APIs. The resources explain
authority boundaries, supported capability names, required tools, failure
semantics, and source provenance.

Do not add a knowledge database, crawler, RAG pipeline, embedding model, or six
new discovery tools for the first release. Server instructions, typed tools,
and these resources provide the smallest proven context surface.

Coverage gates generated from `ToolDescriptor` and skill metadata must produce
empty sets for:

- `uncoveredTools`;
- `unknownSkillIds`;
- `disconnectedSkills`;
- `duplicateToolIds`;
- `duplicateSkillIds`; and
- `unresolvableSkillResources`.

Tool, resource, and skill catalogue ordering is deterministic. `tools/list`
supports the pinned protocol's cursor pagination and is filtered by the
authenticated principal's scopes and deployment-approved descriptor manifest.
It never advertises an unauthorised tool. A visible tool is not removed merely
because an owner is transiently unhealthy; current owner availability and its
observation time are data in `pockethive://capabilities/current`, and invocation
fails with the exact typed owner-unavailable result.

If deployment or reauthorisation changes the visible tool list during an MCP
transport session, the server declares `listChanged` support and sends
`notifications/tools/list_changed`. Transient owner health does not churn the
catalogue. No availability change triggers adapter or environment fallback. The
supported-client matrix proves cursor pagination, resource reads, resource
links, skill-resource retrieval, and list-change handling where used; it never
assumes a non-standard automatic skill loader.

## VS Code extension

The VS Code extension remains TypeScript because it runs in the VS Code
extension host. It becomes a thin authenticated MCP HTTP client:

- it does not spawn the Node or Java MCP server;
- it does not use stdio;
- it stores MCP connection profiles locally as defined above;
- it may cache active session/workflow IDs and revisions for navigation, but it
  does not persist or reconstruct authoritative workflow state;
- it removes direct PocketHive, RabbitMQ, WireMock, TCP Mock, and other backend
  URLs;
- it removes MCP executable path, transport selector, and bundle-root lists;
- deployed scenarios and capabilities come through MCP resources/tools;
- authoring files remain in the active Git workspace; and
- “Upload committed bundle” packages the selected committed path and performs
  the ticketed upload flow using one owner-only bounded temporary ZIP outside
  the workspace, then deletes it at the terminal outcome.

This removes Node/npm from the privileged MCP server, not from VS Code itself.
Extension dependencies must remain minimal, pinned, locked, audited, and
subject to supply-chain scanning.

## Persistence, concurrency, and recovery

The Java migration keeps a small JSON file store because server-side authoring
coordination preserves elicitation-response provenance, restart recovery,
cross-client resume, workflow isolation, optimistic concurrency, and single-use
upload controls. A fully client-carried workflow would lose those guarantees or
replace this store with a more complex cryptographic state capsule.

The store lives at the explicit `PH_MCP_STATE_PATH`, outside any Scenario
Bundle repository. It is coordination state, not another authority for Git
source, user intent, deployed scenarios, runtime operations, or HiveGate
evidence. Owner-query and runtime-lifecycle tools do not use authoring, ticket,
or receipt state. A direct publication may use the explicit ticket and receipt
contracts without requiring an authoring workflow. Any other use requires an
explicitly justified MCP-owned multi-call operation and an `OperationHandle`.

Required properties:

- atomic write-then-rename within the configured volume;
- process and record locking suitable for one service replica;
- principal-bound sessions and workflows;
- optimistic workflow revision checks;
- schema version and explicit incompatible-version failure;
- deterministic session expiry and state rejection after `expiresAt`;
- restart characterisation and corruption tests;
- no bundle archives, secrets, tokens, or source file bodies; and
- configured cleanup of closed/expired sessions and bounded audit,
  operation-handle, and deployment-receipt metadata retention.

The store also enforces the configured per-principal session, per-session
workflow, and total-byte limits before mutation. Limit exhaustion fails with a
typed capacity error and changes no existing record. State and spool directories
are separate owner-only volumes. Production uses platform encryption at rest,
and deployment explicitly excludes the temporary spool from backup. Backup and
restore of coordination metadata is either explicitly configured and tested or
explicitly disabled; there is no undeclared platform default.

Startup validates that the limits are internally consistent and that existing
coordination state does not exceed them. Invalid configuration or excess
existing state fails startup without deleting records. Cleanup is always an
explicit governed or retention-driven action, never a capacity fallback.

A client may retain an opaque session ID, workflow ID, and last observed
revision. Every mutation still reloads the server-side record, reauthorises the
principal, and checks the expected revision. The service rejects a
client-provided replacement workflow snapshot; it never trusts the client to
assert prior elicitation or manufacture a state transition.

Session creation returns `expiresAt`, and every session response reports state
and remaining lifetime. The first release has no implicit renewal and no state
transfer between sessions. Cleanup follows only the explicit configured
durations; missing retention configuration fails startup. Expiry or deletion of
MCP state never claims rollback or deletion of source Git history, Scenario
Manager state, Orchestrator state, or HiveGate evidence.

A write whose outcome is uncertain returns an explicit ambiguous-result error.
The client must reconcile before deciding whether to retry.

Atomic file replacement protects one local record; it is not a transaction with
an owner API. `PublicationAttempt` therefore records the durable boundary before
ticket consumption and before owner mutation. Restart recovery never infers a
successful owner write, replays an owner mutation, or marks evidence complete.
Disk-full, inode exhaustion, read-only mount, lock failure, and process death at
every persisted transition are explicit integration and RST cases.

## Security and failure semantics

The Java MCP is an OAuth protected resource, not a bearer-token proxy. The
current Auth Service contract supports opaque PocketHive tokens resolved through
`POST /api/auth/resolve`; it does not yet define the MCP OAuth authorization
flow, authorization-server metadata, resource indicators, or MCP scopes. The
Java adapter therefore cannot be implemented until the Auth Service owner
extends its canonical contract and that prerequisite passes integration tests.
No second user/grant database or MCP-local token issuer is permitted.

The approved target publishes Protected Resource Metadata and validates the
configured authorization-server identity, audience/resource indicator, expiry,
and scopes on every protected request. It uses exactly one explicit token
validation adapter per deployment: signature validation for a contractually
defined JWT or Auth Service introspection/resolution for a contractually defined
opaque OAuth access token. It never tries one after the other. The VS Code
client is pre-registered for the first release. Phase 0 must approve exactly one
registration mechanism for other supported clients; the service must not
cascade between pre-registration, Dynamic Client Registration, or Client ID
Metadata Documents.

The owning Auth Service contract must also select and define all mandatory flow
controls before implementation:

- PKCE with `S256` and refusal when the authorization-server metadata does not
  advertise support;
- exact registered redirect-URI matching with no wildcard or pattern matching;
- a cryptographically random, principal/client/redirect-bound, short-lived,
  single-use OAuth `state` value and exact callback validation;
- short-lived, single-use authorization codes with replay rejection;
- one explicit refresh-token policy: either no refresh tokens, or documented
  rotation, reuse detection, revocation, and expiry; and
- standards-compliant insufficient-scope challenges and reauthorisation.

These rules remain owned by the Auth Service contract and official MCP/OAuth
specifications; the MCP specification records the release gate rather than
copying their wire schemas.

Required scopes are generated from `ToolDescriptor`. Tool, resource, and skill
discovery is filtered to the principal's scopes, and invocation rechecks the
same contract. The inbound MCP bearer token is never forwarded to Scenario
Manager or Orchestrator. The Java MCP uses the existing Auth Service
`POST /api/auth/service/login` contract to obtain its separately issued,
least-privilege service-principal token. Phase 0 must define the exact grants and
how the original user principal and HiveGate decision are represented in MCP
receipts/evidence without claiming they are downstream credentials. If an owner
operation cannot be safely authorised and audited through that contract, the
tool remains blocked; deployments never fall back to token passthrough or a
second credential mode.

The service must also:

- expose only standards-required protected-resource metadata and approved
  health endpoints without bearer authentication, authenticate every protected
  request, and authorise by principal and tool;
- derive the canonical principal only from verified `(issuer, subject)` and
  bind sessions, workflows, tickets, attempts, and receipts to that principal;
- validate origin and public ingress assumptions, reject an invalid supplied
  origin with HTTP `403`, and never use an MCP session ID as authentication;
- reject arbitrary outbound URLs and arbitrary server filesystem paths;
- enforce compressed size, expanded size, file count, nesting, compression
  ratio, filename, decoded-name collision, duplicate-entry, traversal, symlink,
  hard-link, device-entry, and archive-type limits;
- stream archives with bounded memory and deterministic cleanup;
- redact authorisation headers, secret references where required, bundle data,
  and sensitive owner responses;
- propagate correlation IDs and distinguish them from idempotency keys;
- use owner-supported idempotency only when the owner contract defines it;
- never retry a non-idempotent or ambiguous mutation implicitly;
- expose stable typed failure codes with actionable next steps; and
- emit no approval, ticket, or evidence-complete decision outside HiveGate.

The production container runs as a non-root user with a read-only root
filesystem, no Docker socket, no package-manager/runtime installation, and only
the state and spool volumes writable. The spool is mounted non-executable where
the platform supports it. CPU, memory, file-descriptor, process, state-volume,
and spool-volume limits are explicit. Egress is allow-listed to the configured
PocketHive ingress, approved HiveGate facade, identity provider, and telemetry
collector only; missing access fails rather than opening unrestricted egress.

The build pins Maven plugins and dependencies, verifies checksums where
supported, runs dependency and secret scans, and produces an SBOM. Dependency
updates are reviewed; dynamic versions are forbidden. The image contains an
immutable build manifest with the server identity, protocol revision, Java SDK
version, source revision, `ToolDescriptor` catalogue digest, skill/resource
digests, and SBOM reference. HiveGate pins the approved descriptor/build digest;
runtime mismatch fails rather than silently accepting a changed tool surface.
Use an existing approved image-signing/attestation mechanism when PocketHive
adopts one; do not create MCP-specific signing keys or a second supply-chain
authority in this migration.

## Observability and human oversight

OpenTelemetry spans cover the public ingress request, MCP method, tool
ID/version, non-sensitive stable principal reference, agent session, workflow or
operation handle, owner API call, HiveGate decision reference, state transition,
duration, result/failure class, and cancellation. W3C trace context and
PocketHive `correlationId` propagate end to end. Correlation remains separate
from idempotency as required by PocketHive.

Metrics use only bounded low-cardinality dimensions such as tool ID/version,
operation class, state, and result/failure class. Principal, transport-session,
agent-session, workflow, operation, ticket, receipt, correlation, trace, and
HiveGate decision IDs are prohibited metric labels; they may appear only in
access-controlled traces or redacted logs. Sampled telemetry is operational
diagnostics, not a substitute for unsampled HiveGate approval and evidence
records.

Telemetry and logs must not contain prompts, elicited answers, credentials,
tokens, archive bytes, bundle or test-data bodies, unrestricted worker logs, or
sensitive owner responses. User-visible operation status and evidence show the
current target, state, progress, observation time, approval/decline/cancel
outcome, and safe intervention tools. Metrics distinguish proposed, approved,
declined, started, cancelled, timed-out, reconciled, succeeded, and failed
operations without treating approval as execution success.

## Testing and evaluation

### Characterization and compatibility

Before implementation, capture protocol-level golden tests for every retained
Node tool: schemas, annotations, successful result shape, validation failures,
authorisation failures, and side-effect classification. The Java service must
pass those tests unless the migration ledger records an approved change.

### Unit and property tests

Cover:

- session/workflow state, expiry, retention, and optimistic revisions;
- explicit requirement/capability invalidation, `BLOCKED` recovery, immutable
  published workflows, and rejection of stale downstream evidence;
- rejection of client-carried replacement state and forged answer provenance;
- `ACCEPT`/`DECLINE`/`CANCEL` mapping, required-intent schemas without defaults,
  verified principal binding, and the non-claim of human authorship;
- proof that owner-query and runtime-lifecycle tools create no session,
  workflow, upload ticket, receipt, or mirrored operation state;
- proof that direct publication uses only its defined ticket and receipt state
  and does not require an authoring workflow;
- every requirement disposition and forbidden inference;
- deterministic next-question selection;
- untrusted-content classification, prompt-injection rejection, output-schema
  validation, escaping, and bounded result envelopes;
- scope-filtered discovery, descriptor digest checks, and downstream credential
  isolation;
- capability reconciliation and stale fingerprints;
- deterministic `BundleFileManifest` ordering and rejection of missing,
  additional, renamed, or byte-changed files;
- preservation of every regular bundle file regardless of extension, including
  `.sh`, `.sql`, `.yml`, `.yaml`, `.json`, `.csv`, and `.md`, without execution;
- explicit `CREATE`/`REPLACE` routing with no fallback;
- upload ticket expiry, reuse, principal binding, and purpose binding;
- client-asserted source metadata, archive digest, and canonical bundle-content
  digest binding without a false verified-Git claim;
- `PublicationAttempt` transitions, consumed-ticket behaviour, ambiguous-result
  reconciliation, and prohibition of owner retry;
- ZIP validation, pre-owner verification, traversal/compression-bomb, decoded
  name collision, symlink, hard-link, device-entry, and quota boundaries;
- operation timeouts, monotonic progress, decline, cancellation, reconciliation,
  and stop/abort semantics;
- deployment receipts, resource-link authorisation, and redaction; and
- tool/skill/resource catalogue consistency.

### Integration tests

Use the official interfaces and production-shaped adapters to prove:

- Nginx public-ingress routing for `/mcp` and `/mcp/uploads/{ticketId}`, with no
  direct-container test path;
- exact `2025-11-25` Streamable HTTP initialisation, `POST`, `GET`/SSE,
  resumption, required-header preservation, buffering behaviour, and explicit
  rejection of unsupported revisions;
- cryptographically random transport sessions bound to verified principals,
  authentication on every protected request, missing-session `400`, terminated
  session `404`, selected `DELETE` behaviour, and invalid-origin `403`;
- OAuth Protected Resource Metadata, issuer/audience/resource validation,
  principal-scoped discovery, pre-registered VS Code authentication, and proof
  that inbound bearer tokens are not forwarded downstream;
- PKCE `S256`, exact redirect URI, single-use short-lived `state`, authorization
  code replay rejection, and the approved refresh-token policy;
- rejection of legacy opaque tokens that lack the approved MCP
  resource/scope contract, and use of only the selected token-validation
  adapter;
- Java MCP to Scenario Manager dry-run, create, and replace contracts;
- Java MCP to documented Orchestrator operations;
- authenticated ticketed binary upload, non-secret ticket handles, bounded
  quarantine, complete pre-owner verification, second-upload equality, expiry,
  and cleanup;
- proof with the committed `scenarios/bundles/db-query-postgres-smoke` fixture
  that `.yaml`, `.yml`, `.sql`, `.sh`, and `.md` files retain their relative
  paths and exact bytes through validation and publication;
- proof that malformed, mismatched, interrupted, or quota-blocked uploads make
  zero Scenario Manager create/replace calls;
- atomic file persistence, locking, restart, corrupt-state failure, configured
  quotas, disk/inode exhaustion, read-only mounts, and owner-only permissions;
- process termination and lost responses at every `PublicationAttempt` boundary,
  with no duplicate owner mutation or false success receipt;
- direct-tool execution before and after restart without authoring state, and
  owner-operation status lookup without mirrored MCP state;
- HiveGate-bound mutation intent;
- generated resources, progressive skill retrieval, deterministic cursor
  pagination, list-change notification, catalogue digest pinning, and
  large-result resource links;
- OpenTelemetry propagation, redaction, and oversight events; and
- the same container configuration path locally and in HiveForge.

### Acceptance tests

At minimum prove:

1. A novice with no PocketHive repository receives enough MCP context to
   explain PocketHive, its authorities, current capabilities, and safe next
   action.
2. A QA engineer can describe a goal, answer the guided topics, and produce a
   valid bundle without learning YAML structure.
3. Missing requirements remain visible and block generation; the agent cannot
   infer them.
4. One agent session owns multiple workflows without state leakage.
5. A compatible client can resume an authorised workflow after MCP restart by
   presenting its opaque workflow ID and expected revision; the server reloads
   the server-side coordination record.
6. A committed Git bundle validates and publishes with its client-asserted
   source metadata bound to matching archive and canonical bundle-content
   digests; the result makes no independently verified-Git claim.
7. An old Git commit can be packaged and explicitly republished as rollback.
8. Scenario Manager retains only its current deployed copy and upload
   temporaries are cleaned.
9. Invalid `CREATE` or `REPLACE` fails and never invokes the other operation.
10. Concurrent/ambiguous replace exposes the documented limitation and never
   auto-retries.
11. The MCP starts and all non-memory tools work when HiveMind is absent.
12. A client without elicitation or binary upload support receives the explicit
    capability error.
13. VS Code uses MCP HTTP only, isolates profiles correctly, and never contacts
    backend services directly.
14. Local and HiveForge deployments run the same built image and do not use a
    stale JAR, cached layer, or prior Node server.
15. Unsupported infrastructure operations are absent or explicitly blocked,
    never emulated through direct access.
16. An agent can deploy a previously validated bundle, create/start a swarm,
    inspect or debug it, retrieve evidence, and perform governed cleanup using
    the applicable individual tools without creating or reading authoring
    workflow state.
17. Repository instructions, hostile schemas, logs, examples, tool output, and
    bundle content cannot change the goal, approval, scope, authority, or next
    tool call without explicit user action.
18. A long-running direct operation exposes target, impact, status, progress,
    timeout, and an idempotent cancel/stop path; cancellation never claims
    rollback of completed owner work.
19. Tool and skill discovery is deterministic and principal-scoped; a direct
    tool remains usable without unnecessary skill reads, while a matched complex
    task loads the correct skill and only its required references.
20. Open, closed, and expired session retention behaves exactly as configured,
    and expiry never mutates Git or owning PocketHive services.
21. The Java service rejects unsupported MCP revisions and works only through
    the public ingress; Nginx performs no protocol translation.
22. MCP authentication cannot be replayed as a downstream bearer token, upload
    ticket IDs grant no access by themselves, and secrets do not enter traces,
    logs, results, or state.
23. `scenarios/bundles/db-query-postgres-smoke` validates and publishes without
    dropping or changing `scenario.yaml`, `variables.yaml`, DB templates,
    `compose.yml`, shell scripts, SQL seed data, or its README; no uploaded file
    is executed by MCP.
24. No Scenario Manager mutation occurs until the complete publication archive
    passes transport, archive, quota, and digest checks.
25. Changing an accepted requirement or capability fingerprint invalidates all
    downstream workflow evidence; a published workflow remains immutable.
26. Decline and cancel remain distinct, blocking outcomes and never become
    `NOT_APPLICABLE`, inferred intent, or proof of human authorship.
27. Killing or disconnecting the MCP at each publication boundary never causes
    an automatic duplicate owner mutation or a false success receipt.
28. Transient owner unavailability does not churn the authorised tool list;
    pagination and genuine list-change notification remain deterministic.
29. State and upload quotas, disk exhaustion, and cleanup failure produce typed
    bounded failures without state corruption or unrestricted resource growth.
30. Validation and publication reuse the exact retained client ZIP. Lost,
    changed, or cleaned client bytes require a new validation and cannot reuse an
    earlier validation receipt.

### Mutation testing

Run PIT on all included domain and application decision logic and reach 100%
mutation score. Only generated code, framework bootstrap, and mechanically
delegating adapters may be excluded, with each exclusion reviewed and recorded.
A surviving mutation in state transition, inference prevention, capability
mapping, create/replace routing, ticket binding, digest verification, session
expiry, answer-action mapping, workflow invalidation, publication-attempt state,
pre-owner upload gating, quota enforcement, scope filtering, descriptor pinning,
untrusted-content handling, operation cancellation, output validation, or
security validation blocks cutover.

### Agentic evaluation

Use a versioned corpus split into development, held-out, and adversarial/RST
sets. Record the model and sampling settings; system/developer prompt digests;
MCP client, client policy, and evaluator versions; granted scopes; protocol
revision; Java SDK; server build digest; tool catalogue digest; skill versions;
PocketHive capability fingerprint; and environment starting-state digest for
every run. Retain the Node result as a baseline only where the migration ledger
says behaviour is unchanged.

Repeat nondeterministic trials, record trial counts, success/error rates, and
uncertainty, and have a QA reviewer independently classify ambiguous failures
where practical. Measure correctness first, then:

- correct tool selection;
- correct skill activation and no unnecessary unchanged skill/resource reads;
- required questions asked and unsupported inferences rejected;
- tool calls, retries, and redundant calls;
- token use and completion latency; and
- traceability of the final artifact and operation.

Before release scoring, calibrate evaluators against a versioned labelled sample
and record disagreements and their adjudication. Include negative controls where
the correct result is no tool call, no skill load, a blocking question, or an
explicit refusal. Evaluation covers long-context continuation, client reconnect,
context compaction, stale resource caches, and accidental cross-workflow resume.

The following held-out and adversarial thresholds are absolute release gates:

- inferred required answers: `0`;
- unauthorised tool calls: `0`;
- mutations without the required HiveGate approval: `0`;
- disclosed secrets or sensitive archive/test-data content: `0`;
- `CREATE`/`REPLACE` fallback or ambiguous-write retry: `0`;
- unbounded recursive tool or retry chains: `0`;
- untrusted content changing agent behaviour or authority: `0`; and
- wrong-owner or direct-infrastructure calls: `0`.

Phase 0 must approve explicit thresholds for task completion, correct tool
selection, unnecessary calls/reads, token use, and latency based on the captured
baseline; this specification does not invent performance numbers. Efficiency
improvements are accepted only when correctness, safety, and traceability do not
regress. The corpus reruns after any model, client, protocol, SDK, tool schema,
skill, policy, or capability-catalogue change.

### Rapid Software Testing charters

Exploratory testing is accountable work, not an unstructured demonstration.
Each session has an explicit time box, normally 45–135 minutes, and one concise
mission. Its reviewable session record contains:

- charter ID, mission, target build, risk, and tester;
- model, prompts, client, policy, scopes, protocol, skills, capability
  fingerprint, and environment starting-state digests where an agent is used;
- setup, test data, test ideas, oracles, and relevant variations;
- notes, observations, anomalies, bugs, and evidence references;
- product and risk coverage achieved, important coverage not achieved, and
  blocked investigation;
- automation candidates and follow-up charters; and
- QA-lead debrief, disposition, and unresolved release risk.

This follows the charter, time-box, reviewable-results, and debrief model in the
[Rapid Introduction to Rapid Software Testing](https://www.developsense.com/presentations/2019-04-RapidIntrotoRapidSoftwareTesting.pdf).
The record is evidence, not a claim that elapsed time or test-case count equals
coverage. Run and record sessions for:

- novice ambiguity and misleading examples;
- hostile or contradictory schemas and source material;
- prompt injection, goal hijacking, tool misuse, identity/privilege abuse,
  memory/context poisoning, and human-agent trust exploitation;
- unsupported PocketHive capability requests;
- multi-workflow concurrency, restart, principal isolation, long-context
  compaction, reconnect, and wrong-workflow continuation;
- requirement edits, capability drift, stale generated files, invalidated
  validation, `BLOCKED` recovery, and attempted mutation of `PUBLISHED` work;
- elicitation defaults, dishonest or non-conforming clients, accept/decline/
  cancel handling, and attempts to present a client response as human proof;
- dirty, missing, moved, private, or historical Git sources;
- false Git-source assertions and attempts to confuse client-asserted metadata
  with verified provenance;
- upload interruption, replay, expiry, digest mismatch, quarantine failure,
  disk-full, inode exhaustion, read-only mounts, cleanup failure, process death
  at every publication state, lost owner responses, and ambiguous writes;
- large, malformed, nested, traversal, duplicate, and compression-bomb
  archives, plus decoded-name collisions, symlinks, hard links, and device
  entries;
- mixed-content bundle preservation using
  `scenarios/bundles/db-query-postgres-smoke`, including `.yaml`, `.yml`, `.sql`,
  `.sh`, and `.md`, with attempts to make the MCP execute them;
- client temporary ZIP loss, stale validation receipts, repackaging differences,
  workspace switching, and cleanup after success, cancellation, expiry, or
  failure;
- Scenario Manager temporary storage and current-only retention;
- complete operation without HiveMind;
- owner-boundary attempts through direct infrastructure;
- skill discovery, wrong-tool temptation, cursor pagination, genuine tool-list
  changes, transient owner-health changes, unsupported resource/skill clients,
  and agent efficiency;
- recursive next-action loops, retry storms, repeated approval requests, and
  timeout, token, or call-budget exhaustion;
- over-reading skills/resources, stale cached skills, catalogue reordering, and
  misleading next actions;
- direct operational calls outside the wizard, including attempts to impose a
  hidden authoring-workflow prerequisite;
- forged or replayed workflow snapshots, answer provenance, stale revisions,
  and cross-client resume after restart;
- owner-operation IDs before and after MCP restart, including attempts to
  create mirrored MCP operation state;
- long-call timeout, cancel/status races, user decline, stop failure, and owner
  completion after cancellation;
- OAuth discovery, exact redirect URI, PKCE downgrade, `state` and authorization
  code replay, audience/resource confusion, scope changes, token forwarding,
  ticket guessing, and expired resource links;
- transport-session fixation and hijacking, cross-principal session reuse,
  missing and terminated session IDs, Agent Session confusion, session expiry,
  retention cleanup, and attempts to reuse expired state;
- trace correlation, telemetry redaction, metric-cardinality attacks, sampled
  telemetry versus HiveGate evidence, and human intervention visibility;
- VS Code profile isolation, secret handling, and workspace switching; and
- stale JARs, cached images, mixed Node/Java processes, and deployment drift.

The QA lead debriefs every session. Findings are classified by product risk,
added to automated tests where stable, and resolved or explicitly accepted by
the authorised human owner before cutover. An agent, HiveMind, HiveMap, telemetry
system, or test harness cannot accept a release risk.

## Delivery plan

### Phase 0 — contracts and proof

- approve this specification;
- inventory every Node tool and create the migration ledger;
- update the owning `docs/scenarios/SCENARIO_MANAGER_BUNDLE_REST.md` contract to
  canonically document the already-implemented validation, create, and replace
  endpoints plus regular-file path/byte preservation and POSIX-mode behaviour;
  do not duplicate those contracts in MCP or change Scenario Manager;
- select and pin MCP `2025-11-25`, the official Java SDK, the protocol schema
  digest, and the supported-client conformance matrix;
- prove every target client supports `2025-11-25` elicitation, authenticated
  binary ticket upload, SSE/resumption where used, explicit transport-session
  handling, cursor pagination, resource links, resource/skill reads, and
  list-change notification where used;
- approve the separate Auth Service MCP OAuth extension, then define the Java
  resource-server adapter, other-client registration, scopes, selected token
  validation, exact redirect matching, PKCE, `state`, code replay protection,
  refresh-token policy, downstream service-principal grants, and audit contract;
- define the transparent Nginx routes, header preservation, origin policy,
  buffering, limits, rate controls, and streaming timeouts;
- capture retained-tool characterisation tests;
- define `ToolDescriptor`, result envelope, session/workflow, operation, ticket,
  publication-attempt, receipt, invalidation, retention, quota, telemetry, and
  profile contracts;
- capture the versioned agentic evaluation corpus, Node baseline where
  applicable, absolute safety gates, and approved efficiency thresholds;
- produce and approve the digest-pinned descriptor/build-manifest contract
  consumed by HiveGate; and
- record all missing owner APIs as explicit blockers.

### Phase 1 — mechanical Java port

- build the Java OAuth resource server, exact `2025-11-25` Streamable HTTP
  transport, health, and immutable ingress binding;
- add the transparent public-ingress routes and prove the Java container port is
  not a supported client path;
- port valid tool handlers behind narrow owner API ports;
- port the workflow engine and its bounded atomic file state outside bundle
  roots; keep direct operational handlers independent of that state;
- implement bounded quarantined upload spooling and verify complete archives
  before owner calls;
- implement the descriptor/result catalogue, generated registration, OAuth
  boundary, and OpenTelemetry;
- run characterisation, unit, integration, and mutation gates; and
- keep the Node implementation as the test oracle only, not a second deployed
  product.

### Phase 2 — required boundary changes

- add agent sessions containing multiple workflows;
- add explicit session expiry/retention and long-operation oversight;
- enforce elicited, explicit QA dispositions;
- enforce the untrusted-content boundary and typed output validation;
- replace the duplicate wizard with its connected QA-lead skill;
- add client-asserted Git source metadata, deterministic mixed-file manifests,
  ticketed uploads, validation, explicit publication, crash-safe attempt state,
  and receipts without claiming verified Git provenance;
- publish generated knowledge, capability, tool, and skill resources;
- apply progressive skill disclosure and principal-scoped discovery; and
- remove or block tools that violate owner boundaries.

### Phase 3 — clients and deployment

- update the VS Code extension to connection profiles and MCP-only data access;
- add local `build-hive.sh` installation/deployment;
- add HiveForge deployment of the same immutable image;
- verify persistent volume, auth, public ingress, health, and upgrade handling;
- prove pre-registered VS Code auth and no inbound-token forwarding; and
- prove clean rebuilds against stale JAR/image/cache scenarios.

### Phase 4 — quality and atomic cutover

- pass unit, property, integration, acceptance, security, 100% mutation, agentic,
  and RST gates;
- pass the exact protocol/client conformance matrix and held-out agentic safety
  thresholds;
- run the PocketHive review checklist;
- remove the Node MCP implementation and obsolete config/docs in the same
  cutover change;
- verify no supported client still references stdio, bundle roots, or Node; and
- publish migration and rollback instructions.

There is no period in which Node and Java are independently supported products.

## Acceptance gate

The migration is complete only when all of the following are true:

- the migration ledger has no unreviewed tool;
- retained tool schemas and behaviours pass characterisation tests;
- the owning Scenario Manager REST document is the only contract authority for
  bundle validation/create/replace endpoints;
- MCP `2025-11-25`, the official Java SDK, supported clients, and their
  conformance evidence are pinned; unsupported revisions fail explicitly;
- all MCP and upload traffic uses the public ingress, whose configuration is
  transparent and contains no semantic translation;
- OAuth metadata, audience/resource/scope validation, client registration, and
  downstream credential isolation pass their security gates;
- exact redirect URI, PKCE, OAuth `state`, authorization-code replay, selected
  refresh-token policy, transport-session principal binding, and origin
  rejection pass their security gates;
- the Auth Service MCP OAuth prerequisite is approved and delivered without a
  second user/grant authority or acceptance of legacy unscoped tokens;
- no published tool or skill is disconnected;
- tool discovery is principal-scoped, cursor-paginated, stable across transient
  owner failure, list-change conformant, and descriptor/build-digest pinned by
  HiveGate;
- retained operational tools remain independently callable without wizard
  state;
- direct operational tools create no authoring state and use owner-issued
  operation references wherever the owner supplies them;
- long operations are bounded and observable, and expose typed
  start/status/resume/cancel or authoritative stop semantics where applicable;
- a blank-repository agent can discover PocketHive safely from MCP alone;
- the QA workflow cannot generate with unresolved applicable requirements;
- multiple workflows are isolated within one agent session;
- requirement or capability changes invalidate stale downstream evidence,
  `BLOCKED` recovery is explicit, and published workflows are immutable;
- session expiry, state rejection, and retention cleanup follow explicit
  configuration;
- per-principal, per-session, total-state, concurrent-upload, and spool limits
  fail closed, and the production state/spool/container hardening is proven;
- client-asserted Git source metadata and both digest types bind the exact
  validation and publication intent without a false verified-provenance claim;
- complete archives pass quarantine, quota, structure, and digest checks before
  Scenario Manager mutation, and ambiguous attempts never retry automatically;
- mixed-content bundles retain every regular file's relative path and bytes,
  including the `db-query-postgres-smoke` shell, SQL, YAML, YML, and Markdown
  assets, while MCP executes none of them;
- `CREATE` and `REPLACE` are explicit and never fall back;
- current-only Scenario Manager retention and Git rollback are proven;
- HiveMind is absent from MCP dependencies, configuration, health, and runtime;
- direct infrastructure authority paths are absent;
- untrusted content cannot change goal, scope, authority, approval, skill
  instructions, tool selection, or durable state;
- telemetry is correlated and redacted and exposes meaningful human oversight;
- metrics remain low-cardinality and sampled telemetry is not represented as
  HiveGate evidence;
- all absolute held-out/adversarial agentic thresholds equal zero failures;
- the VS Code extension uses only the configured MCP HTTP endpoint;
- local and HiveForge installations use the same non-stale image;
- every RST charter has a reviewable session record, QA-lead debrief, stated
  coverage limits, and authorised-human disposition for unresolved risk;
- all required automated and RST evidence is recorded; and
- the old Node server and obsolete configuration are removed at cutover.

## Known limitations and explicit decisions

- Scenario Manager replace is last-write-wins until its owning contract gains an
  atomic precondition. This migration does not hide or repair that limitation.
- Historical bundle lookup requires access to the Git repository. If access is
  absent, the operation blocks.
- Repository identity, commit SHA, and bundle path are `CLIENT_ASSERTED` in the
  first release. Verified signed source provenance is out of scope; a future
  attestation contract must use an approved PocketHive authority and remain
  bound to both bundle digests.
- Scenario Manager owns bundle semantics. MCP preserves every transport-safe
  regular file without an extension allow-list but never executes bundle
  content. POSIX mode preservation is not claimed unless the owning Scenario
  Manager contract explicitly guarantees it.
- The file workflow store requires a single MCP replica. Horizontal scaling is
  a future, separately approved design. This is an accepted cost of preserving
  server-enforced elicitation-response provenance, restart recovery,
  cross-client resume,
  optimistic concurrency, and single-use upload controls in the first release.
- Fully client-carried workflow snapshots and signed or encrypted continuation
  capsules are rejected for this migration. They shift recovery and concurrency
  into every client or add a second token/key lifecycle without changing a
  PocketHive authority.
- Clients must support elicitation and binary upload tickets. There is no
  degraded fallback mode.
- Remote cutover is blocked until the Auth Service owner supplies the separately
  approved MCP OAuth contract. Existing opaque PocketHive login tokens are not
  silently reclassified as MCP OAuth access tokens.
- The first release supports only MCP `2025-11-25`. MCP `2026-07-28`, Multi
  Round-Trip Requests, header-routed stateless semantics, and protocol cache
  hints require a separately approved Java-SDK/client migration; Nginx will not
  bridge the revisions.
- MCP Tasks remain excluded from the first release. Long work uses explicit
  PocketHive operation handles and tools until the extension is deliberately
  adopted with conformance evidence.
- Unsupported runtime or mock operations remain unavailable until their owning
  PocketHive API exists.
- HiveMind may independently help an agent remember project context, but it is
  never required, called, configured, or trusted by PocketHive MCP.

## Non-goals

This work does not redesign Scenario Manager, create a Git hosting service,
store every bundle version in PocketHive, add a general durable workflow
platform, make clients authoritative for workflow state, build a knowledge
platform, or make the MCP an autonomous QA decision maker. It is a small,
explicit Java migration that makes the existing agent-facing surface safer,
clearer, remotely deployable, and usable without prior PocketHive knowledge.
