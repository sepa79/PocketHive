# PocketHive MCP Java Migration and QA-Led Authoring Specification

## Status

`IMPLEMENTED / LOCAL CUTOVER VERIFIED`

This is the implementation specification and local acceptance record for
replacing the Node.js PocketHive MCP server with one Java 21 service. HiveForge
artifacts are delivered and contract-validated; a remote deployment remains a
separate governed operation through HiveGate/HiveForge.

## Approved design

Implement a minimal Java migration of the existing PocketHive MCP with these
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
   HiveForge. Remote clients connect to `https://<environment>/mcp`; an explicit
   local-loopback profile may use HTTP. Both use the existing PocketHive public
   ingress, which transparently proxies the pinned MCP transport to the Java
   service. There is no scheme or endpoint fallback.
7. The first release pins MCP `2025-11-25` and the official Java SDK version
   proved by Phase 0. MCP `2026-07-28` is a future, explicit protocol migration,
   not a negotiated fallback.
8. The VS Code extension replaces its five product Tree Views with one modern,
   narrow HTML `WebviewView`. It opens on locally persisted MCP environments,
   uses one explicit `Connect` flow, then presents Hive, Buzz, Journal,
   Scenarios, and Debug as top tabs for the opened environment.

The main accepted trade-off is that Scenario Manager replace remains
last-write-wins because it has no atomic expected-version precondition. Git
provides history and rollback, but cannot make a concurrent replace atomic.

The state boundary deliberately accepts one MCP replica while the first release
uses its file store. This preserves server-enforced elicitation-response
provenance, restart recovery, cross-client resume, optimistic concurrency, and
single-use upload controls without adding a database or changing a PocketHive
service.

The former remote-authentication prerequisite is delivered contract-first in
`docs/architecture/AUTH_SERVICE_API_SPEC.md`. Auth Service remains the single
identity and grant authority and now owns authorization-code + PKCE S256,
resource indicators, exact redirect matching, scopes, metadata, opaque tokens,
introspection, and the pre-registered VS Code client. The MCP accepts only that
audience-bound contract and obtains separate downstream service-principal
tokens; it does not reclassify legacy unscoped tokens or introduce another
identity authority.

## Outcome

A novice can describe the test they need without knowing PocketHive. A QA
engineer can answer ordinary testing questions without knowing the Scenario
Bundle format. The connected agent can then:

- learn PocketHive terminology, architecture, capabilities, constraints, and
  tool usage from the MCP;
- connect from the VS Code Side Bar through an environment-first HTML interface
  that remains usable between 280 and 420 CSS pixels wide;
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
25. The VS Code webview is a presentation adapter. It receives typed view models
    from the extension host and never holds bearer tokens, calls PocketHive
    owners directly, or becomes an authority for environment, workflow, runtime,
    or approval state. Webview reconstruction must bind presentation to the
    newest resolved view; disposal of an obsolete view must not detach its
    replacement or reset the host-owned page, profile, tab, or connection state.
26. The VS Code `Connect` action is one user action with ordered, separately
    observable endpoint validation, authentication, and MCP connection testing.
    It must not merge their outcomes, test after decline or cancellation, or
    fall back to another endpoint or authentication mode.
27. `ui-v2/public/logo.svg` is the canonical PocketHive brand asset. VS Code
    package assets are deterministic platform-specific derivatives, never a
    separately hand-maintained logo design.

## Scope

### Included

- a Java 21 MCP service using Spring Boot, the official Java MCP SDK, and
  hexagonal boundaries;
- authenticated MCP `2025-11-25` Streamable HTTP transport;
- transparent MCP and binary-upload routes through the existing PocketHive
  public ingress;
- an MCP OAuth protected-resource adapter, principal-scoped catalogue
  resources, and explicit downstream credential isolation backed by Auth
  Service;
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
- one responsive VS Code HTML `WebviewView` with environment-first navigation,
  compact top tabs, and a single-column debug drill-down;
- local and HiveForge deployment of the same image; and
- unit, property, integration, acceptance, security, mutation, agentic, and
  Rapid Software Testing evaluation.

### Excluded

- changes to Scenario Manager, Orchestrator, or their persistence models;
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
- changing the VS Code extension away from its supported TypeScript host;
- retaining the current five product Tree Views or spawning a local MCP server
  after atomic cutover; and
- a second PocketHive logo source or hand-maintained VS Code logo geometry.

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
| VS Code product surface | Five Tree Views plus settings and a locally spawned MCP | One narrow HTML `WebviewView`; extension host connects to the selected MCP HTTP endpoint |
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
- `PH_MCP_OWNER_API_BASE`: one fixed deployment-internal base URI for the same
  PocketHive ingress adapter; this separates client-visible identity from
  container routing and cannot be selected or overridden by a tool caller;
- `PH_MCP_PROTOCOL_REVISION=2025-11-25`: the only accepted first-release MCP
  revision;
- `PH_MCP_ENVIRONMENT_HEALTH_PROBE_TIMEOUT`: required positive ISO-8601
  connect/read timeout for each declared environment-health probe;
- `PH_MCP_STATE_MODE=FILE`: explicit persistence mode;
- `PH_MCP_STATE_PATH`: dedicated persistent-volume path;
- `PH_MCP_UPLOAD_SPOOL_PATH`: dedicated quarantined temporary-upload path;
- `PH_MCP_UPLOAD_TICKET_TTL`: explicit ISO-8601 lifetime for a single-use
  validation or publication upload ticket;
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

### Pinned Phase 0 protocol baseline

The first-release implementation baseline is:

| Item | Pin |
|---|---|
| MCP specification | `2025-11-25`, tag object `38c84e9f93ad191d9eb26d92b945d17bd0efcaf3` |
| Official protocol schema | `schema/2025-11-25/schema.json`, SHA-256 `1ffe4c5577974012f5fa02af14ea88df4b7146679df1abaaad497c8d9230ca8a` |
| Official Java SDK | `2.0.0`, annotated tag `7fcb504ce1d7d0898fbfc341d0b5389a7a9623ff`, commit `f56d038409473210c59d6eddef09c4b5cd36042b` |
| JSON binding | `mcp-json-jackson2:2.0.0`; no second MCP JSON mapper |
| Server transport | SDK Servlet Streamable HTTP provider, stateful session mode |
| Client session termination | Supported through authenticated HTTP `DELETE` and tested explicitly |

The build records resolved artifact digests in the immutable image manifest and
fails its dependency-verification gate if they change. A Maven version pin is
not, by itself, supply-chain verification.

The first-release client conformance matrix is capability-based and explicit:

| Client class | Direct read/operation tools | QA interview | Ticket upload/publication |
|---|---|---|---|
| PocketHive VS Code extension shipped by this repository | Required | Required through form elicitation | Required through the extension-host binary upload adapter |
| Other MCP `2025-11-25` client | Supported only after the published conformance suite passes for the exact client/version | Supported only when form elicitation, accept/decline/cancel, and server identity presentation pass | Supported only when a registered trusted host adapter can stream the ticket upload without exposing credentials or bytes to model context |
| Client without a required capability | Only the conforming subset advertised for that principal/client | Fails `ELICITATION_CAPABILITY_REQUIRED` | Fails `CLIENT_CAPABILITY_REQUIRED` |

Registration is not a support claim. The server publishes the exact capability
requirements and does not simulate a missing client feature. The release
evidence names every tested client/version; an untested version remains outside
the supported matrix until the suite is rerun.

## Domain model

The following terms are normative for implementation. Their wire schemas live
in the descriptor-owned contract catalogue; prose here defines their semantics
and must not be copied into competing DTOs or validators.

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

A VS Code `McpConnectionProfile` is a locally persisted connection definition.
It contains an ID, display name, canonical MCP URL, explicit endpoint-security
mode (`REMOTE_HTTPS` or `LOCAL_LOOPBACK_HTTP`), explicit authentication mode,
and a key for any associated secret material. Non-secret profiles use VS Code
`globalState` without settings sync, active selection uses `workspaceState`, and
credentials or OAuth session material use `SecretStorage`. Remote profiles must
use HTTPS. Plain HTTP is valid only for an explicitly selected loopback profile
whose resolved host remains loopback; it never falls back from HTTPS.

Live connection status, principal, server identity, PocketHive version,
capability fingerprint, and observation time are transient owner observations.
The human-facing principal label is the verified Auth Service `username`
claim; the canonical authorization key remains the verified `(issuer,
subject)` pair. The extension never substitutes or exposes the opaque subject
as the display label. These observations are never persisted as facts in the
profile. Opening a saved profile therefore reconnects and revalidates it; it
does not display a cached `Connected` claim.

This is distinct from a PocketHive SUT environment and from Scenario Bundle
environment configuration.

### MCP Connection Attempt — PROPOSED

An `McpConnectionAttempt` is extension-host coordination for the one visible
`Connect` action. It belongs to one unsaved or saved `McpConnectionProfile`,
holds no bearer token, and has one explicit state:

| Current state | Allowed next state |
|---|---|
| `EDITING` | `AUTHENTICATING` after local URL and form validation succeeds |
| `AUTHENTICATING` | `TESTING`, `AUTHENTICATION_FAILED`, or `CANCELLED` |
| `TESTING` | `READY_TO_SAVE`, `CONNECTION_TEST_FAILED`, `AUTHENTICATION_FAILED`, or `CANCELLED` |
| `AUTHENTICATION_FAILED` | `AUTHENTICATING` only through `Sign in again` |
| `CONNECTION_TEST_FAILED` | `TESTING` only through `Retry test`; an expired session moves to `AUTHENTICATION_FAILED` |
| `READY_TO_SAVE` | `SAVED` through `Save & open` or `CANCELLED` |
| `SAVED`, `CANCELLED` | Terminal |

`Connect` always performs endpoint validation, the one configured OAuth flow,
then an authenticated MCP test. It is one user action, not one merged result.
The UI reports authentication and connection-test outcomes separately. Decline
or cancellation invokes no connection test. Failure preserves no inferred
success and never tries another URL, transport, protocol, or authentication
mode.

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
2. The workflow generates a deterministic proposed file set. Generated text is
   byte-stable UTF-8: leading and trailing whitespace, final newlines, and empty
   files are preserved exactly. Relative paths must already be canonical;
   leading or trailing path whitespace and duplicate paths are rejected rather
   than normalized, overwritten, or ordered implicitly.
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

### IDE repository discovery

The VS Code Scenarios tab exposes two explicitly labelled projections:

- `Deployed` is the Scenario Manager catalogue obtained through PocketHive MCP;
- `Repository` is a read-only discovery of authoring candidates in the trusted
  VS Code workspace's Git repositories.

Repository discovery is not a second scenario catalogue, validator, or runtime
authority. It reads committed `HEAD`, matches only canonical
`scenarios/**/scenario.yaml` paths, and presents each matching parent directory
as a candidate for the existing validation and publication flow. It projects
the committed relative file paths needed for local navigation, but does not
parse scenario semantics, execute bundle content, include working-tree bytes,
or claim that a candidate is valid or deployed. Scenario Manager remains the
only semantic validation and deployed-scenario authority.

The extension scans only explicit VS Code workspace folders after VS Code marks
the workspace trusted. No workspace produces `NO_WORKSPACE`; an untrusted
workspace produces `UNTRUSTED`; a non-Git folder or failed Git command produces
an explicit per-folder error. Multi-root workspaces are supported and folders
inside the same canonical repository root are de-duplicated. Discovery is
bounded by exact path, output-byte, and candidate-count limits; exceeding a
limit fails explicitly and never returns a silently truncated candidate set.

The extension host retains the canonical repository root and bundle path. The
webview receives only a bounded display projection, committed relative file
paths, and an opaque candidate ID, then returns that exact ID when the user
selects `Edit`, `Validate`, or `Deploy`. It cannot submit a repository root or
absolute filesystem path. `Edit` opens only a committed relative file retained
for that candidate in the trusted local workspace; missing or unlisted files
fail explicitly and never fall back to Git-object preview. Candidate IDs are
rebuilt on each scan and an unknown or stale ID fails explicitly. The retained
candidate also pins the discovered commit;
validation fails if workspace trust has been withdrawn or repository `HEAD` has
changed, and Git packaging reads the pinned commit objects rather than mutable
working-tree bytes. Validation packages the selected committed tree through
the existing client adapter and ticket upload flow. Manual committed-directory
selection remains a separate explicit action, not a fallback from discovery.

Each Repository candidate is a self-contained disclosure matching the Deployed
scenario-card language. One card is focused at a time. Its own three-column
action row contains `Edit`, `Validate`, and `Deploy`; its own
`Overview | Files | Inputs` drill-down and validation/publication result remain
inside that card. No candidate depends on page-bottom actions. `Files` renders
the committed relative hierarchy and each file action opens the corresponding
working-tree file in the VS Code editor. The UI states plainly that publication
still packages committed `HEAD`, so editing requires an explicit commit and
refresh before validation or deployment.

`Validate` retains the exact validated ZIP and shows owner validation evidence
inside that candidate. `Deploy` is explicit CREATE intent and uses the exact
`scenarioId` and `scenarioName` parsed from `scenario.yaml` by Scenario Manager
and returned in the validation receipt; the extension does not parse or infer
either field. If the deployed Scenario Manager catalogue already contains that
exact ID, the card opens a modal overlay with two explicit paths:

1. `Replace existing` publishes the retained bytes with `REPLACE` and that exact
   owner-reported ID.
2. `Rename source` defaults the proposed ID and name to the validated values
   plus `-01`, opens local `scenario.yaml`, discards the retained bytes, and
   requires the user to edit, commit, refresh, validate, and deploy again.

The suffix is an editable suggestion, not an automatic rewrite or fallback.
There is no CREATE-to-REPLACE retry, no silent identity change, and no
publication of uncommitted bytes.

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
static resources are build-time projections of canonical documents. Each
knowledge document carries its canonical source path and content digest; the
server publishes its build version and catalogue digest separately. The
runtime never downloads new instructions.

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
`docs/ORCHESTRATOR-REST.md`, and relevant contract schemas. Current MCP identity,
binding, grant, state mode, protocol revision, and catalogue digest come from
`pockethive://capabilities/current`. Owner state and availability remain live
owner-tool reads; the capability resource does not cache or mirror Scenario
Manager or Orchestrator state. The resources explain authority boundaries,
supported capability names, required tools, failure semantics, and source
provenance.

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

Tool, resource, and skill catalogue ordering is deterministic. The first
release uses the official Java SDK's immutable complete `tools/list`; every
descriptor declares its required scope and every invocation reauthorises that
scope. Principal-filtered progressive discovery is the canonical
`pockethive://tools/catalogue` and `pockethive://skills/catalogue` resource
pair. The static MCP list is not an authorisation decision.

The first release does not advertise `listChanged` and does not implement a
custom `tools/list` dispatcher merely to add server-side pagination or
principal filtering that the pinned SDK does not provide. The catalogue is
immutable for the process lifetime, so a deployment or grant change requires a
new authenticated connection. Owner health never changes either catalogue;
owner reads and calls fail with exact typed owner errors. No availability
change triggers adapter or environment fallback. The supported-client matrix
proves complete deterministic tool listing, resource reads, skill-resource
retrieval, and scope enforcement; it never assumes a non-standard automatic
skill loader.

## VS Code extension

The VS Code extension remains TypeScript because it runs in the VS Code
extension host. It becomes a thin authenticated MCP HTTP client with one HTML
`WebviewView` registered as `pockethive.companion` beneath the PocketHive
Activity Bar container. This replaces the current Hive, Buzz, Journal,
Scenarios, and Settings Tree Views as product navigation. Command Palette
commands may remain when they are independently useful, but they call the same
application services as the webview and cannot become a second behaviour path.

The target follows the proven HiveGate Companion pattern without creating a
runtime dependency on HiveGate: local TypeScript, HTML, and CSS; a strict
Content Security Policy; typed host/webview messages; and domain/application
logic outside presentation. Do not add React, another UI framework, remote
scripts, remote styles, remote fonts, or a second package manager for the first
release.

Use narrow ports:

| Concern | Owner |
|---|---|
| Profile persistence and active selection | `McpConnectionProfileRepository` adapter over VS Code storage |
| OAuth session | `AuthenticationPort` adapter over the approved MCP OAuth contract and `SecretStorage` |
| MCP initialisation, resources, and tools | `McpClientPort` Streamable HTTP adapter |
| Page and tab state | Presentation controller in the extension host; transient except active profile/tab navigation hints and preserved across webview reconstruction |
| HTML rendering | Pure view-model-to-markup functions and a local webview client |
| Host/webview messages | One discriminated-union contract validated on both boundaries |

The webview receives only bounded, redacted, serialisable view models. The
extension host owns authentication, MCP sessions, tool calls, profile storage,
temporary ZIP handling, and cancellation. The webview never receives a bearer
token, secret reference value, upload bytes, unbounded log body, owner URL, or
raw unvalidated owner response.

### Narrow layout contract

The primary surface is the VS Code Side Bar, not an editor-width dashboard.
Design and acceptance tests use 280, 320, and 420 CSS-pixel widths plus VS Code
zoom and font scaling. The layout has:

- one content column and no page-level horizontal scrolling;
- a compact `← Environments` control followed immediately by a sticky top tab
  strip, with no global logo or duplicated environment header consuming Side
  Bar height;
- one fixed slim environment rail at the viewport bottom. It contains the
  PocketHive hexagon, local environment name, aggregate service state, and the
  accessible account control. It expands upward into the canonical
  `pockethive://environment/health` rows and never duplicates service probes in
  the extension;
- 14-pixel default body text, visible keyboard focus, and controls at least 32
  CSS pixels high;
- wrapped descriptions and ellipsised URLs, IDs, and hashes with an accessible
  full-value title or detail action;
- vertical scrolling with the primary action kept in normal reading order;
- reduced-motion support and no meaning conveyed by colour alone; and
- a horizontally keyboard-scrollable tab strip only below 280 CSS pixels;
  tabs are never silently removed or moved into an inferred navigation mode.

The tab strip implements the WAI-ARIA tabs pattern: one roving tab stop,
`Left`/`Right` and `Home`/`End` navigation, exact `tab`/`tabpanel` bindings, and
the selected tab scrolled into view. Only the tab strip may overflow
horizontally; focus outlines, sticky positioning, and long owner values must
not create page-level horizontal scrolling.

Wider editor panels may add whitespace but must not switch to a separate
multi-column product design. Debug results, forms, and lists remain one column
so the Side Bar contract is the single responsive source of truth.

### Environments page and connection flow

The first page is always `Environments`. It lists locally persisted
`McpConnectionProfile` records and provides `Add environment`. Each row shows
the display name, safely truncated MCP URL, last live connection outcome when
observed in the current extension process, verified principal label when
connected, `Open`, and an overflow menu for explicit edit/remove actions.

The add/edit flow has three visible stages: `Endpoint`, `Connect`, and `Ready`.
The user supplies the name and exact MCP URL. One primary `Connect` button then
drives the `McpConnectionAttempt` state machine:

1. validate and canonicalise the entered MCP URL against the profile's explicit
   endpoint-security mode without endpoint discovery or scheme fallback;
2. run the one configured OAuth flow;
3. after successful authentication, initialise MCP `2025-11-25`, validate the
   expected PocketHive server identity, and read the minimum authorised
   capability resource; and
4. report authentication and connection-test results as separate status rows.

`Save & open` is enabled only in `READY_TO_SAVE`. It persists the non-secret
profile, selects it for the workspace, and opens its environment workspace.
Authentication cancellation or failure exposes `Sign in again` and performs no
test. A test failure exposes `Retry test` and reuses the still-valid OAuth
session; an expired session moves explicitly to `AUTHENTICATION_FAILED`. Neither
action changes the URL, protocol, transport, or authentication mode.

Auth Service owns the browser sign-in and consent presentation as well as the
OAuth behavior. Both pages use semantic server-rendered HTML, one local static
stylesheet, and the canonical `ui-v2/public/logo.svg` copied into the Auth
Service artifact during Maven resource processing. They show the selected
environment purpose, verified client display name, exact resource, and every
requested scope with a plain-language description. They remain responsive,
keyboard accessible, reduced-motion safe, and readable at narrow mobile widths.
No script, remote font, remote style, inferred permission, hidden scope,
alternate authorization endpoint, or OAuth field transformation is allowed.
The VS Code loopback callback landing page is also branded and locally rendered
with inline PocketHive theme tokens and an exact build-generated data-URI copy
of the canonical `ui-v2/public/logo.svg`. Approval, decline, and error hand-off
states therefore use the same brand geometry even though the page is served
from the local extension callback listener rather than Auth Service. The
callback contains no separately maintained SVG, CSS-drawn mark, remote asset,
or asset fallback; its CSP permits only that generated `data:` image.
Presentation tests assert the canonical issuer form actions and static assets;
the existing authorization-code, PKCE, state, redirect, resource, consent, and
token tests remain the behavioral authority.

The status labels have exact meanings:

| Label | Meaning |
|---|---|
| `Connected` | This extension process has an authenticated, initialised MCP connection with a current successful capability observation |
| `Needs sign-in` | No valid OAuth session exists for the profile |
| `Unavailable` | The explicit MCP test failed; show the typed failure and observation time |
| `Not connected` | The saved profile has not been tested in this extension process |

A cached profile never renders `Connected` before revalidation. Selecting a
different profile closes the prior transport session, cancels only cancellable
client work, clears transient page results, and establishes the selected
connection explicitly. It never carries a principal, capability observation,
swarm selection, or Debug result across environments.

### Open environment workspace

`Open` and `Save & open` replace the first page with one environment workspace.
The content header contains only `← Environments`; Refresh remains a current-tab
action. Profile identity, connection/service state, and account actions have one
presentation owner: the fixed footer. There is no region, endpoint selector,
environment options, duplicated identity block, or global logo header. A sticky
top strip contains exactly these icon-led tabs; icons are presentation only and
every tab retains its visible text and accessible name:

| Tab | Purpose and MCP source |
|---|---|
| `Hive` | Swarm list, lifecycle, health, and configuration tools |
| `Buzz` | Bounded hive-wide event timeline |
| `Journal` | Bounded per-swarm journal and evidence views |
| `Scenarios` | Separate deployed Scenario Manager and committed Git-repository views, validation, and publication |
| `Debug` | Orchestrator-backed runtime diagnostics and governed cleanup |

The active tab is presentation state, not MCP or owner state. Refresh re-reads
the current owner-backed data and always shows its observation time. Background
refresh is bounded, pauses while hidden, and cannot mutate or silently retry.
An unavailable owner produces the exact typed state within the selected tab;
the client never switches environment, endpoint, owner, or adapter.

Owner responses use bounded, tab-specific list, card, empty, and error states.
Hive shows swarm identity, lifecycle state, health, and bee count; Buzz and
Journal show bounded event summaries; Scenarios shows Scenario Manager IDs,
names, and folder paths. Raw JSON is available only in an explicit technical
details disclosure or a bounded Debug result; it is not the primary product
view. Journal first requires an exact discovered swarm and then calls
`debug_journal` for that selected swarm without guessing.

The footer summary is one 42-pixel rail. Its left group uses the canonical
PocketHive hexagon and the locally persisted environment display name; its
state text is derived from the latest health resource and authenticated MCP
session. Its right group is the account icon and disclosure control. Expanding
health opens a full-width drawer directly above the rail with square top
corners and no trailing content gap after the final service row. Each compact
row shows the service name, public endpoint, and text-plus-icon state. The
account disclosure is an anchored overlay above the rail; it never consumes
drawer height or leaves reserved whitespace. It identifies the verified
principal and environment and offers only the action valid for the current
session (`Sign in`, `Retry connection`, or `Sign out`).

`pockethive://environment/health` is a read-only dynamic MCP resource with this
bounded shape:

```json
{
  "status": "HEALTHY|DEGRADED|UNAVAILABLE",
  "services": [
    {
      "id": "pockethive-ui",
      "name": "PocketHive UI",
      "endpoint": "https://environment.example/",
      "status": "HEALTHY|UNAVAILABLE",
      "observedAt": "2026-08-21T12:00:00Z"
    }
  ],
  "observedAt": "2026-08-21T12:00:00Z"
}
```

The service order and probe contracts are canonical in one typed, validated
MCP configuration catalogue: PocketHive UI, Orchestrator, Scenario Manager,
Network Proxy Manager, WireMock, TCP Mock, and Grafana. The application layer
receives that catalogue through construction and owns no HTTP topology or
timeout defaults. The HTTP adapter calls only the catalogue's explicit public
paths through the configured `ownerApiBase`; displayed endpoints are resolved
only from `pocketHiveIngress`. One target failure produces one `UNAVAILABLE`
row and aggregate `DEGRADED`/`UNAVAILABLE`, never a second target, protocol,
path, or inferred success. `PH_MCP_ENVIRONMENT_HEALTH_PROBE_TIMEOUT` is a
required positive ISO-8601 duration used for both connect and read timeouts.
The extension reads the resource on open and explicit refresh/tab loads,
retains the last complete view while a load is in flight, and does not add a
visual-strobing background poller.

The workspace restores the independently useful controls from the removed Tree
Views without restoring a second behaviour path:

- each Hive row exposes only the lifecycle action valid for its reported state;
  `Start`, `Stop`, and guarded `Remove` call `swarm_start`, `swarm_stop`, and
  `swarm_remove` through a short-lived least-privilege MCP connection and then
  refresh the authoritative swarm list. `Remove` is disabled unless the
  authoritative projection is fresh, ready, and stopped. The row renders the
  exact Orchestrator bee summaries as a full-width human-readable worker
  disclosure above the swarm action row rather than hiding owner data behind a
  generic Details preview or overlapping popover. Each expanded worker row has
  Inspect and Logs actions. The extension first calls `runtime_list_workers`
  and requires one exact runtime whose instance equals that bee instance before
  invoking the selected diagnostic; zero or multiple matches fail explicitly.
  Its Web UI action
  resolves only the exact `pockethive-ui` endpoint published by environment
  health and opens `/v2/hive/{swarmId}/view`. A historical run never exposes
  Start, Stop, restart, replay, or any other lifecycle action;
- each Hive row has a collapsed run-history disclosure. Opening one row closes
  the previous disclosure and calls the read-only `debug_journal_runs` tool for
  that exact swarm. The tool delegates to Orchestrator's existing
  `/api/swarms/{swarmId}/journal/runs` endpoint and returns its exact run IDs,
  first/last timestamps, entry count, and pinned state; the client does not
  infer pass/fail from recent events;
- selecting one run opens Journal with the exact swarm and run IDs. The
  existing `debug_journal` input therefore accepts optional `runId` as an exact
  owner-side filter. A malformed or blank selected ID fails at the MCP
  boundary; an owner-unknown exact ID preserves Orchestrator's explicit empty
  or not-found response and never falls back to the active or latest run;
- Buzz is a compact one-row event stream with an always-visible search field.
  Time, kind, and severity remain explicit but sit behind one collapsed
  `Filters` disclosure that reports the active-filter count. Journal uses the
  same filter model after a searchable/autocomplete exact-swarm choice. Debug
  uses the same exact-swarm choice pattern. An entered value not present in the
  current bounded MCP result fails explicitly and is never inferred or sent as
  another target. Buzz can open only the top-level `/v2/buzz` Web UI because
  that owner route has no record deep link. Journal can open
  `/v2/journal/swarms/{swarmId}?runId={runId}` only when both exact IDs are
  present. Both routes use the exact `pockethive-ui` health endpoint; the
  extension does not reconstruct it from the MCP URL. Filtering is
  presentation state over the bounded owner page and never changes endpoint,
  owner, adapter, or target. Each event disclosure retains its exact technical
  details action. Buzz and Journal do not duplicate a Debug shortcut; swarm
  diagnostics remain owned by the dedicated Debug tab and Hive swarm actions;
- Scenarios uses explicit `Deployed | Repository` source tabs. `Deployed` rows
  are compact, searchable disclosures around the existing Scenario Manager
  catalogue. `Repository` groups the active trusted workspace's committed
  `HEAD` candidates by canonical Git repository and commit, identifies them by
  repository-relative bundle path, and sends only an opaque candidate ID back
  to the extension host for validation. Its
  exact-folder selector remains behind a collapsed filter control so the search
  row stays usable at Side Bar width. The
  narrow Side Bar keeps one focused scenario at a time and uses an internal
  `Overview | Files | Inputs` drill-down rather than a second page. `Overview`
  presents Description, Controller, and Bees as full-width rows without
  truncation. `Files` is the single file-navigation surface: it uses read-only
  MCP inspection tools backed by Scenario Manager's bundle workspace API to
  render the deployed paths as a nested directory hierarchy and open exact file
  previews without direct owner calls from the extension. Redundant summary,
  `scenario.yaml`, schema, and template shortcut buttons are not duplicated
  above the drill-down. Bundle-workspace file inspection remains the structured
  file payload contract. `Inputs` shows exact
  bundle-local SUT
  descriptors through MCP plus explicit presence and preview actions for
  `variables.yaml` and `authProfiles.yaml` when they exist; and
- Debug retains every canonical action in one target-first diagnostic workspace.
  A compact `Worker | Swarm` context control navigates between the primary
  runtime target and the swarm tools. Worker discovery sits beside the exact
  swarm selector; the exact discovered runtime remains visible above compact
  `Logs | Inspect | Version` tabs, with their evidence directly below.
  Logs render the selected runtime's bounded Docker stdout/stderr response;
  Inspect renders the same bounded Orchestrator inspect projection used by
  `ui-v2`; Version renders the exact image/label projection returned by that
  owner API. MCP and the extension do not call Docker or create a second runtime
  diagnostic contract.
  Swarm-level reads use a two-column tool matrix, and guarded Maintenance stays
  visually separate with `Cleanup plan` labelled `Plan only`. Every control
  still invokes the one existing `debugToolCall` mapping.

`debug_journal_runs` is additive and read-only. Existing MCP clients and
`debug_journal` calls without `runId` keep their current behaviour. The MCP
catalogue remains the single tool/skill source of truth, and the new tool is
connected to `runtime-diagnostics` like the related journal reads.

The additive read-only scenario inspection surface follows the same rule:
existing `scenario_list`, `scenario_get`, and deployed-file reads keep their
current behaviour, while new exact bundle-tree, bundle-file, and bundle-local
SUT reads remain MCP-first projections of existing Scenario Manager APIs.

### Debug tab

Debug uses a target-first single-column drill-down sized for the VS Code Side
Bar. The user selects an exact swarm, explicitly loads its workers, and selects
an exact discovered runtime where required. `Logs`, `Inspect`, and `Version`
are compact modes of one worker evidence panel. The remaining swarm-level
actions form a compact two-column matrix below it. Selecting any action renders
its bounded result adjacent to the active action context; large output uses the
authorised expiring resource link defined by the tool contract. The view-model
keeps the last bounded worker-discovery result separate from later diagnostic
evidence so an action result cannot erase the exact-worker selector.

| UI action | Canonical MCP tool |
|---|---|
| `Workers` | `runtime_list_workers` |
| `Logs` | `runtime_tail_worker_logs` |
| `Version` | `runtime_get_worker_version` |
| `Inspect` | `runtime_inspect_worker` |
| `Runtime drift` | `runtime_diff_swarm_runtime` |
| `Control plane` | `runtime_control_plane_status` |
| `Rabbit topology` | `runtime_rabbit_topology_snapshot` |
| `Timeline` | `runtime_swarm_timeline` |
| `Manifest` | `runtime_manifest_validate` |
| `Cleanup plan` | `runtime_cleanup_plan` |

Logs require an explicit bounded tail and show target plus observation time.
Worker-specific actions remain disabled until an exact discovered worker is
selected. The webview never guesses a worker, queue, run, or manifest.

`Cleanup plan` is visually separated as a guarded action. The plan renders the
exact target, candidate set, running-resource state, and hash beside a locked
`Execute cleanup` control. The extension does not call
`runtime_cleanup_execute` directly: execution remains disabled until a
HiveGate-governed approval and execution path is available. The MCP, extension,
agent, or telemetry cannot approve the plan. Decline, expiry, stale hash, or
cancellation returns to the plan without executing or automatically requesting
approval again.

The fixed environment drawer prefixes every known service ID with one explicit
Codicon mapping. An unrecognised service ID uses the neutral globe icon only;
it does not infer a service type from its name, endpoint, protocol, or port.

### Webview security, accessibility, and assets

The webview must:

- set `default-src 'none'`, load only extension-local images/styles and
  nonce-bound local scripts, and use no inline event handlers;
- validate every inbound and outbound message against the canonical
  discriminated-union contract and reject unknown types or fields;
- render untrusted values through text nodes, never `innerHTML`, and apply the
  same output bounds and redaction as the tool contract;
- support keyboard-only operation, logical focus restoration, screen-reader
  labels, `aria-live` status, VS Code high-contrast themes, and 200% zoom; and
- dispose listeners, MCP subscriptions, timers, resource links, and temporary
  results when the view or selected environment closes.

`ui-v2/public/logo.svg` remains the single source for PocketHive brand geometry
and colour. The packaging build deterministically produces:

- a mark-only `currentColor` silhouette at
  `vscode-pockethive/resources/activity-mark.svg`, optically simplified for the
  24 px Activity Bar mask with visible rounded connectors, nodes, body, lens
  ring, and centre button; and
- a bounded full-colour mark at
  `vscode-pockethive/resources/logo-mark.svg` for compact swarm identity; and
- a bounded CSS brand token at
  `vscode-pockethive/resources/brand-tokens.css` that colours the selected
  workspace tab, with accessible light and high-contrast theme treatment; and
- a callback-safe data-URI module at
  `vscode-pockethive/src/generated/callbackLogo.ts` containing the exact
  canonical SVG bytes for the local OAuth hand-off page.

Action and navigation icons use the official VS Code Codicon font from one
exact pinned `@vscode/codicons` development dependency. The build copies only
its local CSS, font, and licence into the packaged extension; the webview CSP
permits that local font source and no remote icon or font source. Asset drift
and the VSIX allow-list remain packaging gates.

All four generated derivatives carry source path and digest metadata, are not
edited by hand, and are checked during packaging. The Activity Bar silhouette
preserves the canonical hexagon/network/lens identity while remaining legible
under VS Code active, inactive, hover, and high-contrast treatment.

The extension does not spawn the Node or Java MCP server, use stdio, or retain
MCP executable paths, transport selectors, bundle-root lists, direct
PocketHive/RabbitMQ/WireMock/TCP Mock URLs, or legacy product Tree Views after
cutover. Deployed scenarios and capabilities come through MCP resources/tools.
Authoring files remain in the active Git workspace. `Upload committed bundle`
packages the selected committed path and performs the ticketed upload flow
using one owner-only bounded temporary ZIP outside the workspace, then deletes
it at the terminal outcome.

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

State schema version 2 adds the Scenario Manager-owned `scenarioName` to
validation receipts. Startup performs one explicit version 1 to version 2
migration. It preserves sessions, workflows, generated files, publication
attempts, and receipts that already contain a non-blank owner name. A legacy
receipt without that owner fact, and any publication ticket that depends on
that receipt, is invalidated so the caller must validate the committed bundle
again. The migration never derives a scenario name from the scenario ID or
bundle path. Unsupported schema versions still fail startup without changing
the stored evidence.

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
and remaining lifetime. Agent workflow sessions have no implicit renewal or
state transfer between sessions. The VS Code companion's separate OAuth
environment session is authorised once for the exact UI capability intent,
narrowed by Auth Service to the principal's current grants, and renewed through
the canonical Auth Service refresh grant. Renewal is scheduled before expiry
and checked on demand, is single-flight, rotates an opaque refresh token, and reconnects a
candidate MCP transport before replacing the current transport. The
authenticated workspace and its last good owner data remain visible during
renewal, so a transient session state never sends the user back to
`Environments` or blanks the active tab. Commands never launch a separate
browser grant, and cleanup remains outside the companion scope. Cleanup follows only the explicitly
configured durations; missing retention configuration fails startup. Expiry or
deletion of MCP state never claims rollback or deletion of source Git history,
Scenario Manager state, Orchestrator state, or HiveGate evidence.

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

Required scopes are generated from `ToolDescriptor`. Principal-oriented tool
and skill resources are filtered to the caller's scopes; the immutable protocol
tool list exposes each descriptor's required scope, and invocation always
rechecks it. The inbound MCP bearer token is never forwarded to Scenario
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
- `McpConnectionAttempt` transitions, one ordered `Connect` action, separate
  authentication/test outcomes, cancellation before test, and save gating;
- connection-profile storage boundaries, environment switching, transient live
  status, and rejection of cross-environment principal, capability, selection,
  and result leakage;
- exhaustive typed host/webview message decoding, view-model redaction, unknown
  message rejection, focus restoration, and disposal behaviour;
- exact Debug label-to-tool mapping, explicit target selection, bounded log
  inputs, and proof that cleanup execute cannot be reached without a current
  reviewed plan;
- every requirement disposition and forbidden inference;
- deterministic next-question selection;
- untrusted-content classification, prompt-injection rejection, output-schema
  validation, escaping, and bounded result envelopes;
- scope-filtered catalogue resources, invocation checks, descriptor digest
  checks, and downstream credential isolation;
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
  principal-scoped catalogue resources, pre-registered VS Code authentication, and proof
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
- generated resources, progressive scope-filtered skill retrieval,
  deterministic complete tool listing, catalogue digest pinning, and bounded
  results;
- the VS Code `Connect` sequence through public ingress, including OAuth cancel,
  auth failure, test failure, retry-test, expired-session reauthentication,
  server-identity rejection, and successful save/open;
- one `pockethive.companion` webview contribution, strict CSP, local-only
  resources, typed messages, SecretStorage isolation, and absence of legacy
  product Tree Views or local MCP process spawning in the packaged VSIX;
- deterministic Activity Bar and webview logo derivatives from
  `ui-v2/public/logo.svg`, including package-content and source-digest checks;
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
13. VS Code uses MCP HTTP only, isolates profiles correctly, exposes one HTML
    `pockethive.companion` view, and never contacts backend services directly.
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
19. Tool and skill discovery is deterministic: the protocol tool list carries
    explicit required-scope metadata, principal-filtered catalogue resources
    provide progressive disclosure, every call reauthorises, and a direct tool
    remains usable without unnecessary skill reads.
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
28. Transient owner unavailability does not churn the immutable tool list or
    the principal-filtered catalogue resources.
29. State and upload quotas, disk exhaustion, and cleanup failure produce typed
    bounded failures without state corruption or unrestricted resource growth.
30. Validation and publication reuse the exact retained client ZIP. Lost,
    changed, or cleaned client bytes require a new validation and cannot reuse an
    earlier validation receipt.
31. The VS Code extension always opens on Environments, and `Open` enters a
    single selected environment with sticky Hive, Buzz, Journal, Scenarios, and
    Debug tabs.
32. One `Connect` action validates the entered endpoint, authenticates, then
    tests the expected PocketHive MCP. Authentication and test results remain
    distinct; cancellation performs no test; `Save & open` remains disabled
    until both succeed.
33. The complete environment and Debug flows remain keyboard- and screen-reader
    usable at 280, 320, and 420 CSS pixels, 200% zoom, dark/light/high-contrast
    themes, and reduced motion without page-level horizontal scrolling.
34. Debug is one column, never guesses a target, maps every visible action to
    its canonical authorised tool, bounds logs/results, and exposes cleanup
    execute only from a reviewed current plan through HiveGate governance.
35. The webview rejects forged/unknown messages and injected markup, receives no
    tokens or secrets, loads no remote code/content, restores focus after state
    changes, and releases listeners, timers, links, and results on disposal.
    Resolving a replacement view before an older view disposes keeps the
    replacement attached and immediately restores its host-owned view model.
36. The Activity Bar displays the exact PocketHive mark as a theme-tinted icon,
    compact swarm identity may use its full-colour derivative, and the selected
    tab uses the canonical Hive colour. All are generated from
    `ui-v2/public/logo.svg` and pass package provenance checks; the workspace has
    no separate global logo header.

### Mutation testing

Run PIT on included Java domain/application decision logic and Stryker on
included VS Code TypeScript domain/application/presentation decision logic.
Each reaches a 100% mutation score. Only generated code, framework bootstrap,
static markup, CSS, and mechanically delegating adapters may be excluded, with
each exclusion reviewed and recorded.

A surviving mutation in state transition, inference prevention, capability
mapping, create/replace routing, ticket binding, digest verification, session
expiry, answer-action mapping, workflow invalidation, publication-attempt state,
pre-owner upload gating, quota enforcement, scope filtering, descriptor pinning,
untrusted-content handling, operation cancellation, output validation,
connection-attempt sequencing, save gating, environment isolation, webview
message validation, Debug tool mapping, guarded cleanup presentation, or
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
- skill discovery, wrong-tool temptation, immutable complete tool listing,
  transient owner-health changes, unsupported resource/skill clients, and agent
  efficiency;
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
- VS Code profile isolation, secret handling, and workspace switching;
- narrow webview widths, zoom, long translated/content values, keyboard-only and
  screen-reader operation, sticky tabs, focus restoration, theme changes, and
  extension reload while connecting or viewing a Debug result;
- combined Connect sequencing, browser-auth cancellation, successful auth with
  failed MCP initialisation, retry after token expiry, false cached Connected
  state, and server-identity mismatch;
- hostile host/webview messages, untrusted log/owner markup, remote-resource
  injection, oversized result rendering, listener/timer leaks, and disposal
  during an in-flight call;
- Debug target ambiguity, narrow log output, tool-scope changes, missing owner
  capability, stale cleanup plans, and attempts to reveal cleanup execute as a
  direct action; and
- stale JARs, cached images, mixed Node/Java processes, and deployment drift.

The QA lead debriefs every session. Findings are classified by product risk,
added to automated tests where stable, and resolved or explicitly accepted by
the authorised human owner before cutover. An agent, HiveMind, HiveMap, telemetry
system, or test harness cannot accept a release risk.

### Delivery evidence and RST debrief — 2026-08-18

This is local-development evidence for the worktree based on commit
`08ee6d67d654b06d709a0f51763b96057906c3cb`. It is not a HiveGate approval,
execution ticket, remote HiveForge deployment receipt, or production release
decision.

| Gate | Final evidence | Result |
|---|---|---|
| Java verification | Maven `verify`: Auth Service 12 tests and MCP 113 tests | Pass, 125/125 |
| Java mutation | PIT: 1,374/1,374 mutated lines covered; 569/569 mutants killed; no survivor or timeout | Pass, 100% |
| VS Code verification | `npm run package`: 57 tests passed twice, cutover/assets/package checks passed, 24-file VSIX produced | Pass |
| VS Code mutation | Stryker: 749/749 actionable mutants killed; three platform/composition mutations explicitly reviewed and ignored | Pass, 100% |
| Supply chain | OSV: 65-component MCP SBOM, 58-component Auth SBOM, and extension lockfile reported no known issue; `npm audit` reported zero vulnerabilities | Pass |
| Local deployment | Canonical targeted `build-hive.sh` rebuild produced and restarted only the final MCP image; service became healthy with the declared read-only, non-root, bounded-volume/tmpfs controls | Pass |
| Public-ingress conformance | OAuth code + PKCE S256, exact metadata/resource, six scopes, no refresh token, code replay rejection, MCP `2025-11-25`, server `0.15.35`, 49 tools, nine connected skills, and session close all passed through `http://localhost:8088` | Pass |
| Mixed bundle | The extension packaged the committed `scenarios/bundles/db-query-postgres-smoke` tree as 11 exact files/6,122 bytes; validation and explicit `REPLACE` consumed the same retained ZIP, and Scenario Manager recorded the same byte count with zero findings | Pass |
| Architecture review | HiveMap documentation-conflict and code-quality scans completed; stale Node/stdio guidance and duplicate OAuth-scope ownership were fixed and recorded as resolved findings | Pass |

#### RST session `RST-MCP-CUTOVER-20260818`

- **Mission:** challenge the final Java MCP, OAuth, publication, extension, and
  deployment cutover at their highest-risk boundaries and follow every anomaly
  until it is explained, fixed, or identified as uncovered risk.
- **Time box / actual:** 90 minutes / 80 minutes, 20:30–21:50 Europe/London.
- **Target:** branch `feat/pockethive-mcp-improvements`, local image
  `pockethive-mcp:latest`, MCP `2025-11-25`, server `0.15.35`, VSIX `1.0.0`.
- **Tester and oracles:** Codex acting as QA lead; owning contracts, PocketHive
  rules, exact owner API responses, immutable catalogue counts, state-machine
  invariants, mutation thresholds, public-ingress logs, and Scenario Manager
  byte-count/findings evidence.
- **Variations sampled:** unauthenticated and authenticated MCP; exact OAuth
  metadata; first/reused consent behavior; authorization-code replay; no-refresh
  policy; session create/use/close; all-scope discovery; mixed shell/SQL/YAML/YML/
  Markdown bundle preservation; explicit replace; archive/digest equality;
  non-root/read-only runtime; stale build inputs; documentation/implementation
  authority drift; and dependency advisories.

The fix loop found and resolved:

1. OAuth scope literals had two owners. The MCP now consumes
   `PocketHiveMcpScopes` from `auth-contracts`, and a catalogue invariant rejects
   any non-canonical required scope.
2. The final PIT run killed every mutant but exposed two compiler-mapped record
   lines without value-semantics coverage. Focused record equality, hash,
   accessor, and string tests raised line coverage from 1,372/1,374 to
   1,374/1,374 without weakening the threshold.
3. Active documentation still described Node/stdio startup and the old plugin
   document set as current, while an OmniMCP concept still presented that
   removed integration as implementable direction. Current Java HTTP guidance
   now has one entry point; both historical areas are explicitly superseded.
4. The exploratory OAuth probe initially revoked its own access token by testing
   code replay before MCP use. Reordering the scenario proved successful MCP use
   first and replay rejection afterward; the observation confirms replay is not
   a harmless retry.
5. Existing authorization consent legitimately skipped the consent screen. The
   final probe covered that branch explicitly instead of assuming every login
   displays consent.
6. Provisional review notes said eight connected skills. The canonical registry,
   catalogue resource, and live discovery all proved nine; the evidence was
   corrected without changing the product.

**QA-lead debrief:** no unresolved local correctness, mutation, packaging,
public-ingress, bundle-integrity, or known-dependency defect was found. The
session produced stable regression tests for the code issues and durable HiveMap
resolution evidence for the architecture issues. It did not accept the following
release risks:

- remote HiveForge deployment and HTTPS/identity-provider operation still need
  a governed HiveGate/HiveForge execution and receipt;
- native VS Code manual checks at 280/320/420 CSS pixels, 200% zoom, keyboard,
  screen reader, light/dark/high-contrast themes, and reduced motion still need
  a human accessibility session;
- nondeterministic held-out/adversarial agent trials across approved model and
  MCP client versions still need the versioned corpus, recorded sampling
  settings, calibrated evaluator, repeated trials, and authorised QA review
  defined above; deterministic server and client safety contracts passed but do
  not substitute for model-behavior qualification; and
- physical disk-full/inode exhaustion, abrupt host/process termination at every
  publication boundary, and remote network-loss timing remain production-shaped
  resilience charters even though their deterministic failure/state transitions
  are covered in automated tests.

These items block a claim of production release acceptance. They do not block
the recorded `IMPLEMENTED / LOCAL CUTOVER VERIFIED` status.

### VS Code live-UI follow-up and RST debrief — 2026-08-19

This is additional local-development evidence for the uncommitted worktree on
`feat/pockethive-mcp-improvements`. It is not a HiveGate approval, remote
HiveForge receipt, commit, push, or production release decision.

| Gate | Final evidence | Result |
|---|---|---|
| MCP verification | Full MCP reactor test run: 115 tests | Pass, 115/115 |
| MCP mutation | PIT: 1,313/1,313 mutated lines covered; 521/521 mutants killed, zero survivors or uncovered mutants; four non-terminating/slow mutants were killed by the configured timeout | Pass, 100% |
| VS Code verification | `npm run package`: 65 tests passed twice, cutover/assets/package allow-list passed, extension 1.0.5 VSIX produced | Pass |
| VS Code mutation | Stryker: all 803 actionable mutants killed by assertion; zero survivor, timeout, error, or uncovered mutant, including every payload-boundary, collection-cap, exact-byte-limit, tab-mapping, and view-lifecycle mutant | Pass, 100% |
| Live UI acceptance | Playwright completed browser OAuth and MCP reads through the public local ingress, captured nine screenshots, and found zero Axe, page-overflow, clipping, target-size, raw-primary-JSON, tab-binding, roving-focus, keyboard, or active-tab visibility issue | Pass |
| Responsive variation | Dark Side Bar sampled at 140, 240, 280, 320, and 480 CSS pixels, including the 140-pixel 200%-zoom equivalent and deliberately long environment/principal labels | Pass |
| Supply chain | Playwright and Axe are exact dev-only pins; `playwright-core` is locked to the same exact version; `npm audit --audit-level=high` reported zero vulnerabilities | Pass |
| Local deployment | `build-hive.sh --quick --module pockethive-mcp-service` reused caches, rebuilt the exact JAR/image, preserved unrelated containers/volumes, and restarted only the MCP service | Pass |
| Installed extension | VS Code CLI replaced `pockethive.pockethive-vscode@1.0.4` with packaged version `1.0.5`; the real window was explicitly reloaded and the saved local profile was reauthenticated through browser OAuth | Pass |
| Native Buzz regression | Against live local MCP data, the installed extension opened Buzz three times, refreshed Buzz, and completed Hive -> Buzz navigation without losing the profile, connection, selected tab, cards, or control responsiveness | Pass |

#### RST session `RST-VSCODE-COMPANION-20260819`

- **Mission:** add the explicit local MCP environment through the real browser
  flow, challenge the narrow companion against live owner data, and follow every
  layout, identity, interaction, and test-harness anomaly through a fix loop.
- **Oracles:** this specification, canonical logo assets, verified OAuth/MCP
  identity, owner response contracts, WAI-ARIA tabs, no-page-overflow and
  no-fallback rules, Playwright geometry, Axe, and before/after screenshots.
- **Variations:** empty and populated owner results, historical journal events,
  long labels, keyboard tab traversal, 140–480 CSS-pixel widths, current/local
  OAuth consent, selected-tab visibility, and cached JAR/image risk.

The session found and resolved:

1. `principalLabel` exposed the opaque UUID subject. Authenticated transport now
   keeps `(issuer, subject)` as the authorization key but exposes the verified
   Auth Service `username` as the separate display label.
2. The sub-320 media rule changed page padding without changing the tab strip's
   negative margin, producing exactly three pixels of page overflow. Both now
   consume one CSS variable.
3. Tabs lacked panel bindings, roving focus, arrow/Home/End behavior, and active
   visibility. The WAI-ARIA pattern and deterministic scroll-into-view behavior
   are now acceptance-tested; all tabs fit at 280 pixels and only the strip may
   scroll below that width.
4. Hive, Buzz, and Scenarios presented owner JSON as the primary product view,
   while Journal could not request one exact swarm. Tab-specific bounded cards,
   explicit empty/error states, collapsed technical details, and exact-swarm
   `debug_journal` selection replace that behavior.
5. The first exploratory Journal probe reused a swarm ID found only in
   historical hive events and correctly received owner HTTP 404. The harness
   now permits Journal reads only for IDs in the current `swarm_list`; it does
   not infer that historical evidence is a live target.
6. Re-rendered screenshot states retained the prior long page's scroll offset,
   making the next header appear clipped. Resetting only the harness viewport
   scroll removed the false product finding.
7. A 140-pixel variation representing a 280-pixel Side Bar at 200% zoom found a
   30-pixel header/footer overflow. A compact sub-200 layout fixed it without a
   second navigation mode or hidden controls.

No current swarm existed during the final local run, so the live selected-swarm
Journal success state remains a native/manual or future seeded-acceptance
charter. The exact command boundary, no-inference target validation, empty
state, owner 404 behavior, and `debug_journal` MCP mapping are automated; this
residual does not justify inventing or silently creating a swarm.

#### RST session `RST-VSCODE-WEBVIEW-LIFECYCLE-20260819`

The installed 1.0.3 companion was observed in the real VS Code window after a
Buzz interaction showing its empty startup Environments model with every
control inert. Read-only VS Code storage inspection proved that the exact saved
local profile still existed, the extension-host log showed no MCP or profile
failure, and the standalone live MCP/Buzz flow remained healthy. This isolated
the failure to the webview reconstruction boundary rather than owner data,
OAuth, storage, or the local MCP.

The first hypothesis was a stale disposal callback detaching a replacement
view. Version 1.0.4 added a correct `CurrentView` ownership guard and its tests,
but native retesting reproduced the same symptom. Live extension-host debugging
then proved the visible view was current, its command reached Java, and
`postMessage` returned success. The lifecycle hypothesis therefore did not
explain this failure and is retained only as a separate defensive hardening.

The decisive probe measured the 50-event Buzz owner result at 128,239
characters. `postView` bounded that field and then bounded the complete model a
second time. The second bound replaced the required root fields (`page`,
`profiles`, `activeTab`, and `busy`) with a generic truncated-content object.
The renderer correctly treated the now-invalid model as its empty Environments
state; every subsequent response repeated the same root replacement, which
made the view appear frozen. A ten-event owner probe measured 37,525 characters.

The corrected smallest design applies the size/redaction boundary only to the
five untrusted owner-data fields and never replaces the view-model root. An
oversized field becomes an explicit `COMPANION_VIEW_DATA_TOO_LARGE` error with
no copied content, while required navigation and profile state remain intact.
Buzz and Journal use one named ten-event Side Bar limit. TDD records the former
compile failure, root-contract preservation, every independently bounded field,
redaction, exact UTF-8 byte-limit behaviour, collection caps, and exact
tab-to-tool mapping. Version 1.0.5 carries this correction. All 65 extension
tests passed, all 803 actionable Stryker mutants were killed by assertion, the
VSIX package and public-ingress Playwright run passed, and the installed
extension completed three native Buzz loads, a Buzz refresh, and Hive -> Buzz
navigation against live local MCP data without returning to Environments or
freezing.

One exploratory native replay appeared to return to Environments after a Hive
click. Capture-before-click reproduction showed that focusing the real window
had changed the scrolled webview position while the external test driver reused
the old absolute coordinate. Repeating the product path without the stale
coordinate completed Hive -> Buzz successfully. This is retained as an RST
test-harness learning, not classified as a product defect or silently omitted.

### Authorised-session and enterprise-UI follow-up — 2026-08-19

This is local-development evidence for the uncommitted worktree on
`feat/pockethive-mcp-improvements`. It supersedes the earlier no-refresh policy
as the current companion-session design. It does not change the historical
evidence recorded above and is not a HiveGate approval, remote HiveForge
receipt, commit, push, or production release decision.

| Gate | Final evidence | Result |
|---|---|---|
| Auth verification | Auth Service unit and integration suites | Pass, 22/22 |
| MCP verification | Full MCP reactor test run | Pass, 115/115 |
| VS Code verification | Extension compile, test, cutover, asset, and package checks | Pass, 102/102 tests |
| Auth mutation | PIT covered 56/56 mutated lines and killed 26/26 mutants | Pass, 100% |
| MCP mutation | PIT covered 1,391/1,391 mutated lines and killed 576/576 mutants | Pass, 100% |
| VS Code mutation | Stryker killed 1,825/1,825 actionable mutants; zero survivor, timeout, error, or uncovered mutant | Pass, 100% |
| Public-ingress session proof | Base refresh rotated its token; retired-token replay failed; explicit sign-out revoked both current tokens | Pass |
| Live UI acceptance | Playwright exercised 20 environment, workspace, account, tab, narrow-width, sign-in, and consent screenshots with zero recorded finding | Pass |
| Supply chain | `npm audit --audit-level=low` | Pass, zero vulnerability |
| Local deployment | Canonical targeted `build-hive.sh` rebuild deployed the current Auth Service; the dependent MCP restarted after its cached service token was invalidated | Pass |

#### RST session `RST-VSCODE-AUTHORISED-SESSION-20260819`

- **Mission:** preserve an authorised workspace across base-session expiry,
  make sign-in and sign-out understandable, and challenge the implementation at
  OAuth, ingress, transport-switch, reconstruction, and narrow-layout
  boundaries.
- **Oracles:** the canonical Auth Service contract, exact public MCP resource,
  PocketHive no-fallback rules, rotating-token invariants, verified principal,
  current-transport ownership, last-good-data continuity, WAI-ARIA and Axe,
  geometry checks, and same-input design comparisons.
- **Variations:** expiry outside and inside the 60-second renewal boundary;
  concurrent callers; successful and failed refresh; successful and failed
  candidate MCP initialisation; rotated-token replay; revocation; privileged
  scopes; malformed discovery and stored session data; webview reconstruction;
  long labels; 140–480 CSS-pixel widths; 200%-zoom equivalent; sign-in,
  consent, account-menu, retry, and sign-out states.

The fix loop found and resolved:

1. Refresh requests through the public ingress were redirected to sign-in and
   returned HTTP 406. The public-client converter compared the raw request URI
   with `/oauth/token`; Nginx's forwarded prefix made that URI
   `/auth-service/oauth/token`. It now derives one canonical application path
   from the validated context path and fails closed when the path is
   inconsistent. Unit tests cover direct, prefixed, and inconsistent paths,
   and the live public-ingress refresh proves rotation.
2. The Account disclosure panel remained laid out while closed because author
   CSS overrode the browser's native closed-state rule. At the 140-pixel
   200%-zoom equivalent it caused page overflow, and a long principal caused
   overflow at 280 pixels. The closed panel is now explicitly removed from
   layout, controls wrap within their container, and the sub-320 workspace
   header stacks without hiding an action.
3. Replacing the active transport immediately after a token refresh could have
   discarded a working session when MCP initialisation failed. Renewal now
   creates and validates one candidate connection, swaps only on success, and
   then closes the old connection. Concurrent calls share one in-flight
   renewal.
4. Treating every OAuth grant as renewable would have retained privileged
   authority. Only the exact base scope set receives a refresh token;
   privileged scoped tool sessions remain short-lived and non-renewable.
5. Rebuilding Auth Service invalidated the MCP process's cached downstream
   service token. Restarting only that dependent local service restored the
   declared token lifecycle without rebuilding unrelated images or clearing
   caches, containers, or volumes. This remains local deployment learning, not
   an automatic runtime fallback.

**QA-lead debrief:** no unresolved local correctness, mutation, public-ingress
session, package, account-flow, responsive-layout, or known npm dependency
defect was found. During restore and renewal, the extension keeps the workspace,
active tab, and last good owner data rendered; it exposes one calm session
notice instead of navigating to Environments or blanking the page. Explicit
sign-out revokes current access and refresh tokens, clears VS Code Secret
Storage, closes the MCP transport, and then returns to Environments.

The local evidence does not qualify remote identity-provider behaviour,
production network loss, cross-device logout, screen-reader announcements in a
native VS Code window, or governed HiveForge deployment. Those remain explicit
release charters; they do not justify a fallback or weaken the current local
acceptance result.

### Status-rail visual-system follow-up — 2026-08-20

This is local-development evidence for the uncommitted worktree on
`merge/rewrite-lifecycle-mcp`. It changes only the VS Code presentation adapter,
its packaged local assets, tests, and this specification. It is not a HiveGate
approval, remote deployment receipt, commit, push, or release decision.

| Gate | Final evidence | Result |
|---|---|---|
| VS Code verification | Compile, 119 unit/integration tests, asset provenance, and atomic cutover checks | Pass |
| VS Code mutation | Stryker killed 2,033 mutants by assertion, killed nine by timeout, retained eight declared ignores, and reported zero survivor, uncovered mutant, or error | Pass, 100.00% |
| Live UI acceptance | Public-ingress OAuth/MCP flow; 54-tool catalogue; 25 environment, auth, tab, drill-down, and narrow-width captures | Pass, zero finding |
| Accessibility and resilience | Axe plus geometry at 140, 240, 280, 320, 428, and 480 CSS pixels | Pass, zero overflow, clipping, undersized target, or Axe violation |
| Supply chain | Exact pinned official Codicon development package and `npm audit --audit-level=low --ignore-scripts` | Pass, zero vulnerability |
| Packaging | Asset drift and 37-file extension allow-list; local font, CSS, and licence included | Pass |
| Design QA | Six normalized page comparisons plus focused Scenario Files/Overview comparisons and Inputs evidence in `vscode-pockethive/design-qa.md` | Pass |

#### RST session `RST-VSCODE-STATUS-RAIL-20260820`

- **Mission:** apply the approved compact enterprise visual system without
  losing migrated functionality, inventing health facts, or weakening exact
  target selection.
- **Oracles:** this specification, current MCP catalogue/view models, SSOT and
  NFF rules, the approved six-page visual set, VS Code accessibility semantics,
  package allow-list, and public-ingress browser evidence.
- **Variations:** empty and connected environments; active and unavailable
  sessions; all five tabs; running and ready swarms; expanded run history;
  advanced event and folder filters; exact combobox input; mixed Scenario files;
  Scenario Inputs; selected Debug swarm; long owner values; 140–480 CSS pixels;
  browser OAuth sign-in/consent; refresh-token rotation and replay rejection.

The fix loop found and resolved:

1. The first approved visual showed service and mock counts before the MCP
   exposed them. The follow-up contract adds one canonical read-only MCP health
   projection. The extension consumes that projection and still performs no
   direct service probe or second health normalisation path.
2. The first custom searchable choice lacked an explicit accessible name. The
   combobox now has one exact label, `aria-autocomplete=list`, an integrated
   listbox, exact-choice validation, and no inferred-target path.
3. Replacing text-only affordances invalidated one static cutover selector and
   one browser interaction selector. Both gates now assert the semantic
   icon-led controls rather than preserving obsolete visible text.
4. The 140-pixel 200%-zoom equivalent initially compressed two tabs below the
   minimum target and clipped the health label. The ultra-narrow tab strip now
   scrolls explicitly, the health label truncates deliberately, and diagnostic
   headings remain usable without page overflow.
5. The first visual evidence captured Scenarios and Debug before their existing
   detailed states were selected. The final acceptance captures the exact
   mixed `.yaml`/`.sh` file tree, Variables/Auth/SUT/endpoint Inputs, and a
   selected Debug swarm rather than presenting empty shells as proof.

The final implementation keeps Start, Stop, guarded Remove, Details, Debug,
batch lifecycle, single-swarm run history, Buzz/Journal filtering, Scenario
inspection, and grouped diagnostics. The shared visual system adds no owner
call, alternate endpoint, background poller, inferred target, or compatibility
fallback. Remaining remote/native-VS-Code manual checks are release charters,
not evidence gaps in this local presentation change.

### Git Scenario discovery implementation follow-up — 2026-08-21

This is local-development evidence for the uncommitted worktree on
`merge/rewrite-lifecycle-mcp`. It adds only the VS Code Git-authoring projection
and reuses the existing Scenario Manager validation/publication path. It is not
a commit, push, deployment receipt, or change to Scenario Manager or MCP tools.

| Gate | Final evidence | Result |
|---|---|---|
| Contract and SSOT review | Repository-wide search found one Git discovery implementation; Scenario Manager remains the only deployed catalogue and semantic validator | Pass |
| Unit and integration | TypeScript build plus 133 extension tests, including real temporary Git repositories, multi-root de-duplication, trust, stale IDs, pinned commits, mixed files, coordinator reuse, messages, boundary, and UI projection | Pass |
| Mutation | All 19 mandated files: 2,246 mutants killed by assertion, nine killed by timeout, zero survived, uncovered, or errored | Pass, 100.00% |
| Live UI acceptance | Public ingress `http://localhost:8088/mcp`, OAuth rotation/replay rejection, 54-tool catalogue, 27 screenshots including Repository scenarios | Pass, zero finding |
| Package | Asset provenance, atomic cutover, and 39-file VSIX allow-list | Pass |
| Actual repository proof | Committed `HEAD` exposed 48 canonical candidates; `scenarios/bundles/db-query-postgres-smoke` packaged as 11 files and retained `.sql` and `.sh` | Pass |

#### RST session `RST-VSCODE-GIT-SCENARIOS-20260821`

- **Mission:** make committed Scenario Bundles in the active IDE repository
  discoverable without creating another scenario authority, leaking host paths,
  or admitting mutable working-tree bytes.
- **Oracles:** this specification, `docs/scenarios/README.md`, Scenario Manager
  bundle REST contract, SSOT/NFF rules, existing ticket upload coordinator, VS
  Code workspace-trust contract, and the Git object model.
- **Variations:** no workspace; untrusted workspace; non-Git folder; nested
  multi-root folders; SHA-1 and SHA-256 commits; invalid UTF-8 and unsafe paths;
  exact and exceeded output/path/candidate limits; duplicate descriptors;
  uncommitted additions; removed worktree directory; changed `HEAD`; stale and
  hostile webview candidate IDs; mixed `.yaml`, `.sql`, and `.sh` bundles; and
  Side Bar rendering through the public MCP ingress.

The fix loop found and resolved:

1. A simple filesystem scan would include dirty bytes and duplicate Scenario
   Manager semantics. Discovery now reads only Git `HEAD` paths and never parses
   bundle meaning.
2. Sending a path from the webview would make an untrusted presentation surface
   select host files. The webview now returns one opaque, scan-owned ID; only the
   extension host resolves its canonical reference.
3. Passing that reference back as a mutable worktree directory left a symlink
   and deletion time-of-check/time-of-use gap. Packaging now reads the pinned
   commit's Git objects and succeeds even when the worktree directory is gone.
4. A candidate could become stale after repository `HEAD` moved or workspace
   trust was withdrawn. Both cases now fail explicitly and require refresh;
   neither silently packages a different revision.
5. The first mutation run exposed redundant path guards and under-specified
   boundary cases. Path validation was consolidated into one canonical helper,
   and exact tests now cover encoding, ordering, every bound, trust, commit
   drift, and stale registry state. The final full mutation run has no survivor.

### Environment health footer implementation follow-up — 2026-08-21

This is local-development evidence for the uncommitted worktree on
`merge/rewrite-lifecycle-mcp`. It adds one canonical MCP-owned environment
health projection, the public-ingress TCP Mock route required by that
projection, and the selected VS Code footer presentation. It is not a commit,
push, remote deployment receipt, or release decision.

| Gate | Final evidence | Result |
|---|---|---|
| Contract and SSOT review | `pockethive://environment/health` is the sole IDE health projection; the extension performs no direct service probe, fallback, or second normalisation | Pass |
| MCP verification | Full MCP reactor unit/integration run, including Streamable HTTP resource read and official-ingress probe contracts | Pass, 127/127 |
| MCP mutation | PIT covered 1,393/1,393 mutated lines and killed 541/541 mutants; zero survived or lacked coverage | Pass, 100% |
| VS Code verification | TypeScript build, 135 tests, asset provenance, atomic cutover, and package-content checks | Pass |
| VS Code mutation | Stryker killed 2,253 mutants by assertion and nine by timeout; zero survived, lacked coverage, or errored | Pass, 100.00% |
| Local deployment | `build-hive.sh --quick --service ui --service pockethive-mcp`; only the selected services were recreated and the official ingress remained healthy | Pass |
| Live UI acceptance | Public-ingress OAuth/MCP flow, 54 tools, session rotation and replay rejection, exact health/account geometry, and 28 browser captures | Pass, zero finding |
| Design QA | Option-1 source and live 428 × 917 capture normalized in `vscode-pockethive/design-qa.md` | Pass |
| Packaging and install | 39-file extension payload allow-list; 41-entry VSIX; forced local replacement of extension 1.0.5 | Pass |

#### RST session `RST-VSCODE-ENVIRONMENT-HEALTH-20260821`

- **Mission:** replace the duplicate workspace identity header with one compact
  footer-owned identity, service health disclosure, and account overlay without
  inventing health facts, adding a background poller, or losing narrow-width
  usability.
- **Oracles:** this specification, `docs/mcp/README.md`, SSOT/NFF rules, the
  selected option-1 visual, owner health contracts, the official public ingress,
  VS Code accessibility semantics, and package/mutation thresholds.
- **Variations:** all services healthy; one unavailable service; all services
  unavailable; probe runtime failure; executor failure; interrupted collection;
  missing or malformed MCP resource; disconnected MCP client; closed and open
  footer; open account overlay; authenticated, restoring, and expired sessions;
  280 and 428 CSS-pixel widths; live token rotation and replay rejection.

The fix loop found and resolved:

1. The extension previously had only MCP-session health and could not honestly
   render service status. The MCP now owns one bounded seven-service projection
   derived only through the selected environment's public ingress.
2. TCP Mock had no public Nginx route, so an official-ingress probe resolved to
   SPA content. The UI and HiveForge ingress contracts now expose the exact
   `/tcp-mock/` adapter; no direct-port test or fallback was introduced.
3. The first extension mutation run exposed four missing fail-closed resource
   assertions. Exact disconnected, default-code, and missing-resource tests
   killed those mutants; the rerun reached 100.00%.
4. The first PIT run found five health failure-path mutants and one existing
   ambiguous-publication accessor without coverage. Interruption, executor
   failure, complete unavailable projection, Spring constructor, and exact
   attempt-ID tests raised mutation and mutated-line coverage to 100%.
5. Removing the visible workspace identity block also removed the level-one
   heading. A screen-reader-only environment heading restored page semantics
   without restoring visual headspace; the second 28-state browser run reported
   zero Axe or layout finding.
6. Live WireMock was unavailable. The footer reports that owner result and the
   aggregate degraded state explicitly; it does not substitute the generated
   visual's healthy example or silently retry another endpoint.

The final footer uses the packaged PocketHive hexagon, square disclosure top
corners, divider-led full-width service rows with no trailing gap, and an
absolute account overlay. Health is refreshed with the normal bounded workspace
read/explicit refresh lifecycle; no independent high-frequency poller or
strobing presentation was added.

### Hive hierarchy and runtime-diagnostics proof — 2026-08-21

This is local-development evidence for the uncommitted worktree on
`merge/rewrite-lifecycle-mcp`. It is not a commit, push, remote deployment
receipt, or release decision.

| Gate | Final evidence | Result |
|---|---|---|
| Contract-first review | Orchestrator REST now documents its actual flattened `observation` projection; MCP readiness consumes that single owner contract | Pass |
| Java verification | Full Orchestrator, Controller, and MCP reactor unit/integration run; no failing Surefire report | Pass |
| MCP mutation | PIT covered 1,432/1,432 mutated lines and killed 613/613 mutants | Pass, 100% |
| VS Code verification | TypeScript build, 141 tests, asset provenance, atomic cutover, and 41-file package allow-list | Pass |
| VS Code mutation | Stryker killed all 2,382 viable mutants: 2,373 by assertion and nine by timeout | Pass, 100% |
| Live MCP lifecycle | Disposable swarm create, 3/3 readiness, start, Logs, Version, Inspect, stop, and remove through `http://localhost:8088/mcp` | Pass |
| Visual and accessibility | 33 Playwright captures at responsive widths; equal three-section Hive action grid; no Axe, overflow, clipping, console, or page finding | Pass |
| Supply chain and package | npm audit found zero vulnerabilities; extension 1.0.5 packaged and forcibly reinstalled | Pass |

#### RST session `RST-VSCODE-HIVE-RUNTIME-20260821`

- **Mission:** reproduce the approved Side Bar hierarchy exactly, prove each
  worker diagnostic against its owning MCP tool, and preserve the existing
  swarm while testing lifecycle behavior.
- **Oracles:** the approved 428 × 917 visual, `docs/ORCHESTRATOR-REST.md`, this
  specification, SSOT/NFF rules, Docker label contracts, MCP catalogue, and the
  public-ingress testing rule.
- **Variations:** expanded and collapsed workers, running and ready swarms,
  disabled running removal, exact worker Logs/Inspect/Version, stale and fresh
  observations, malformed readiness responses, literal build placeholders,
  narrow widths, health drawer open/closed, and lifecycle cleanup.

The fix loop found and resolved:

1. Swarm secondary actions were visually adjacent but did not own three equal
   horizontal sections. CSS and Playwright geometry now require equal-width
   `Debug | Open in Web UI | Remove` cells across the full action row.
2. Live `swarm_wait_ready` still parsed a removed Node-era control-envelope
   shape. It now reads only the canonical Orchestrator REST projection and
   fails explicitly on malformed owner state.
3. The first disposable proof exposed a literal `@project.version@` Docker
   label. Controller resource filtering now packages the canonical Maven
   release value, with a regression test at the artifact boundary.
4. The first post-fix PIT run exposed one blank-state mutant; the missing
   boundary assertion killed it. The next run exposed record-line coverage
   attribution only; source formatting removed that instrumentation artifact
   without changing behavior, and the final gate reached 100% line and mutation
   coverage.

The proof used a uniquely named disposable swarm and removed it after stop.
The pre-existing `test-*` containers were neither started, stopped, nor
removed.

### Event-page presentation boundary follow-up — 2026-08-22

Buzz and Journal use one master-detail presentation boundary. The extension
host receives each canonical ten-event owner page and replaces its `items` with
an allowlisted `CompanionEventSummary` projection before posting the page to
the webview. A summary contains only an opaque `detailId` and the fields needed
by the existing row, filters, and navigation: `eventId`, `timestamp`,
`severity`, `kind`, `type`, `swarmId`, `runId`, `origin`, `direction`,
`routingKey`, and `summary`. Missing optional fields remain absent; the
extension does not infer them. Owner pagination fields remain unchanged.

The complete owner records remain only in a transient extension-host registry
keyed by the opaque IDs created for the current page generation. The webview
can return only one exact `detailId` through `openEventDetails`; it cannot send
an event object, owner ID, index, path, or environment. Refreshing the event
page replaces the generation. Selecting another tab, changing or closing the
environment, signing out, and disposing the provider clear the registry. An
unknown or stale ID fails explicitly with `EVENT_DETAIL_NOT_AVAILABLE`; an
opaque-ID collision while creating a page fails with
`EVENT_DETAIL_ID_COLLISION`. No earlier page, owner call, index, or other
environment is used as a fallback.

`Open technical details` opens the selected complete record in a VS Code JSON
preview rather than expanding raw JSON inside the Side Bar. The preview applies
the companion's single recursive secret-key redaction policy to authorization,
token, secret, and password values. It does not apply a byte-based redaction,
truncate strings or collections, or change non-secret evidence. Raw event
payloads and log snapshots never enter the webview message.

All projected event pages use the existing general 64 KiB fail-closed webview
field limit; the temporary 1 MiB event exception is removed. Tests must prove
that ten large `runtime-log-snapshot` records produce a small page containing
all ten summaries and no raw logs, that exact current IDs return their records,
that replacement and clearing invalidate earlier IDs, that hostile messages
cannot inject records or extra fields, that secret values are redacted only in
the preview projection, and that the Buzz and Journal UI sends the selected
opaque ID. Integration evidence must exercise the public MCP ingress and show
that the Side Bar remains usable with the current large owner page.

#### RST session `RST-VSCODE-EVENT-PAGE-BOUNDARY-20260822`

- **Mission:** reproduce `COMPANION_VIEW_DATA_TOO_LARGE` against current local
  owner data, identify the exact field, and keep complete event evidence
  available without sending it through the Side Bar webview boundary.
- **Oracles:** the ten-event Side Bar contract, per-field fail-closed boundary,
  valid structural view model, public-ingress-only integration rule, complete
  redacted event details, and zero silent fallback or truncation.
- **Observed data:** the live Buzz result was 291,528 UTF-8 bytes. Each of its
  ten `runtime-log-snapshot` records carried about 28 KiB of legitimate
  `extra.logs` evidence. The same probes measured the swarm list at 9,171
  bytes, the template catalogue at 30,500 bytes, and the superseded
  complete-inspect prototype result at 9,522 bytes, isolating the event-page
  policy as the smallest owner.
- **Superseded result:** 143/143 extension tests passed. Stryker produced 2,395 mutants;
  all 2,387 viable mutants were killed (2,378 by assertion and nine by
  timeout), with zero survivor, uncovered mutant, or error. The live
  public-ingress Playwright run preserved the 291,528-byte Buzz page, captured
  34 states, and reported zero accessibility, overflow, console, or page
  finding. The package allow-list and VSIX build passed, npm audit reported
  zero vulnerabilities, and extension 1.0.5 was forcibly reinstalled.

That result validated the diagnosis but its 1 MiB transport exception is
superseded by the master-detail contract above. The replacement must record its
own current unit, mutation, public-ingress UI, package, audit, and installed
extension evidence before delivery.

Runtime diagnostics remain owner projections. Acceptance verifies that MCP and
the extension preserve the exact version and bounded inspect response returned
by the established Orchestrator endpoint also used by `ui-v2`; they do not add
Docker access, require new runtime labels, or infer deployment-wide versions.

## Delivery plan

### Phase 0 — contracts and proof

- record this approved specification as the implementation baseline;
- inventory every Node tool and create the migration ledger;
- update the owning `docs/scenarios/SCENARIO_MANAGER_BUNDLE_REST.md` contract to
  canonically document the already-implemented validation, create, and replace
  endpoints plus regular-file path/byte preservation and POSIX-mode behaviour;
  do not duplicate those contracts in MCP or change Scenario Manager;
- select and pin MCP `2025-11-25`, the official Java SDK, the protocol schema
  digest, and the supported-client conformance matrix;
- prove every target client supports `2025-11-25` elicitation, authenticated
  binary ticket upload, SSE/resumption where used, explicit transport-session
  handling, complete deterministic tool listing, and resource/skill reads;
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
- define and approve the VS Code `McpConnectionProfile`,
  `McpConnectionAttempt`, environment-workspace view model, host/webview message,
  navigation, and Debug-action contracts before changing the extension;
- define the exact expected MCP server identity, minimum capability observation,
  endpoint-security modes, OAuth-session validity rule, and typed connection
  failures used by `Connect`;
- capture the current VS Code contribution/configuration migration ledger and
  approve the atomic removal of product Tree Views, local MCP spawning, stdio,
  bundle roots, transport selectors, and direct backend URLs;
- approve the 280/320/420-pixel, 200%-zoom, keyboard, screen-reader, reduced-
  motion, and VS Code theme acceptance baselines;
- define the deterministic logo-derivation and VSIX package-provenance contract
  rooted at `ui-v2/public/logo.svg`;
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
- apply progressive skill disclosure and principal-scoped catalogue resources; and
- remove or block tools that violate owner boundaries.

### Phase 3 — clients and deployment

- replace the VS Code product Tree Views atomically with one
  `pockethive.companion` HTML `WebviewView` and MCP-only data access;
- implement profile/attempt/navigation/Debug domain types, application
  coordinators, and narrow storage/authentication/MCP client ports before the
  VS Code and webview adapters;
- implement the environments-first flow, ordered `Connect` state machine,
  environment workspace, sticky top tabs, and single-column Debug drill-down;
- generate and package the Activity Bar and compact identity assets deterministically from
  `ui-v2/public/logo.svg`;
- remove local process spawning, stdio, direct backend access, bundle-root and
  transport settings, legacy Tree View contributions, and their dead commands;
- add unit, integration, VSIX-package, accessibility, responsive, security, and
  Stryker mutation gates for the extension;
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

## Repository scenario cards implementation follow-up — 2026-08-23

The VS Code Repository source now presents each committed Scenario Bundle as a
self-contained card. Each card owns its `Overview`, `Files`, and `Inputs`
views plus `Edit`, `Validate`, and `Deploy`. `Edit` opens the exact committed
file path in the VS Code editor. Validation packages the exact HEAD tree and
uses only Scenario Manager's returned `scenarioId` and `scenarioName`; the
extension does not parse or infer either field.

Deployment has two explicit paths. A missing deployed ID uses `CREATE`. An
existing ID opens a conflict dialog with `Replace existing` and `Rename source`.
Replace publishes the retained validated bytes with `REPLACE`. Rename suggests
the exact `-01` suffix, opens local `scenario.yaml`, and requires the user to
edit, commit, refresh, validate, and deploy again. It does not rewrite bundle
bytes or fall back to replacement. Validation evidence stays in its scenario
card; no duplicate raw result is rendered below the list.

State schema version 2 preserves the new owner-provided scenario name across
restart. The explicit version 1 migration preserves sessions, workflows,
generated files, publication attempts, and complete receipts. It invalidates
only receipts missing the owner name and publication tickets that depend on
them, so those bundles must be validated again. Unsupported or malformed state
still fails startup.

Qualification evidence:

- Scenario Manager: 171 tests passed.
- PocketHive MCP: 131 tests passed, including Streamable HTTP integration and
  restart migration tests.
- VS Code extension: 154 unit, asset, and cutover tests passed.
- Java PIT: 572/572 mutations killed, 1,447/1,447 mutated-class lines covered,
  zero survivors and zero uncovered mutants.
- Extension Stryker: 2,611/2,611 mutations killed, including nine bounded
  timeout kills, with zero survivors, uncovered mutants, or errors.
- Browser acceptance: 35 authenticated public-ingress captures across 140,
  240, 280, 320, 428, and 480 CSS-pixel widths; zero page, console,
  accessibility, clipping, overflow, or target-size findings.
- Local deployment: canonical `build-hive.sh` rebuilt only Scenario Manager
  and PocketHive MCP as required. The persisted v1 state migrated without
  deleting its volume, and the public OAuth metadata and MCP ingress recovered.

RST exposed two defects before handoff. A clean service build found one stale
Java caller and one nondeterministic nanosecond test fixture. The first
post-deployment browser run then found the v1 receipt incompatibility, and the
visual comparison exposed the duplicate raw validation projection. Each defect
entered the red-test, fix, full-regression, and mutation loop before this
evidence was recorded.

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
- protocol tool discovery is complete and immutable with explicit required
  scopes; progressive catalogue resources are principal-filtered; invocation
  reauthorises; owner failure does not churn discovery; and the descriptor and
  build digests are available for HiveGate pinning;
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
- the VS Code extension contributes exactly one product HTML WebviewView, opens
  on Environments, and uses only the selected profile's MCP HTTP endpoint;
- saved profiles never imply a live connection; every open/reload revalidates
  authentication, server identity, and the minimum capability observation;
- one `Connect` action preserves distinct endpoint, authentication, and MCP-test
  outcomes, performs no test after cancellation, and enables `Save & open` only
  after complete success;
- Hive, Buzz, Journal, Scenarios, and Debug use the same authorised MCP catalogue;
  Debug is single-column, requires exact targets, and exposes cleanup execute
  only from a reviewed current plan through HiveGate;
- the webview passes its responsive, accessibility, CSP, message-validation,
  redaction, disposal, theme, and 200%-zoom gates at 280, 320, and 420 CSS pixels;
- the packaged VSIX contains deterministic derivatives of
  `ui-v2/public/logo.svg` and contains no legacy product Tree View, local MCP
  spawning, stdio, bundle-root, transport-selector, or direct-backend path;
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
- Remote use requires the explicit HTTPS ingress/host and Auth Service MCP
  secrets declared by the HiveForge contract. Existing opaque PocketHive login
  tokens are not silently reclassified as MCP OAuth access tokens. Live remote
  deployment and approval remain governed HiveGate/HiveForge operations rather
  than implementation evidence created by this branch.
- The first release supports only MCP `2025-11-25`. MCP `2026-07-28`, Multi
  Round-Trip Requests, header-routed stateless semantics, and protocol cache
  hints require a separately approved Java-SDK/client migration; Nginx will not
  bridge the revisions.
- MCP Tasks remain excluded from the first release. Long work uses explicit
  PocketHive operation handles and tools until the extension is deliberately
  adopted with conformance evidence.
- Unsupported runtime or mock operations remain unavailable until their owning
  PocketHive API exists.
- The VS Code Side Bar is the only first-release layout contract. Below 280 CSS
  pixels only the tab strip may scroll horizontally; there is no separate wide
  dashboard or alternate navigation mode.
- Replacing the five product Tree Views and legacy local-MCP configuration is an
  intentional atomic cutover, not a backward-compatible presentation mode.
- Node/npm remains part of the VS Code extension toolchain because VS Code runs
  TypeScript extensions. It is removed from the privileged MCP server, while
  extension dependencies remain minimal, pinned, locked, audited, and package-
  verified.
- HiveMind may independently help an agent remember project context, but it is
  never required, called, configured, or trusted by PocketHive MCP.

## Non-goals

This work does not redesign Scenario Manager, create a Git hosting service,
store every bundle version in PocketHive, add a general durable workflow
platform, make clients authoritative for workflow state, build a knowledge
platform, or make the MCP an autonomous QA decision maker. It is a small,
explicit Java migration that makes the existing agent-facing surface safer,
clearer, remotely deployable, and usable without prior PocketHive knowledge.
