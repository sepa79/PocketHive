# TCP Mock Functional Equivalence Specification

Status: proposed requirements; implementation and qualification pending.
Source audit: PocketHive `aedd336b`. No runtime tests were performed for this audit.

## Decision

TCP Mock must provide WireMock's core testing workflows for framed TCP messages:
stubbing, matching, templates, stateful scenarios, delays/faults, request
verification and real proxy/record/replay. Every workflow works headlessly.
This document owns those requirements and their qualification matrix.
[Global SUT and mocks](../../docs/todo/global-sut-mocks-spec.md) owns provisioning,
configuration publication, shared lifecycle and access through PocketHive.

**Functional equivalence (proposed):** equivalent testing capabilities using
TCP payloads and connections. It does not require HTTP status/header semantics,
WireMock JSON/API/SDK compatibility, Handlebars syntax or a standalone UI.
The comparison baseline is
[WireMock OSS 3.13.2](https://github.com/wiremock/wiremock/releases/tag/3.13.2),
verified against its official release tag. This is a reference pin, not a claim
about the version deployed by PocketHive.

Workflow references: [matching](https://wiremock.org/docs/request-matching/),
[verification](https://wiremock.org/docs/verifying/),
[stateful behaviour](https://wiremock.org/docs/stateful-behaviour/) and
[record/playback](https://wiremock.org/docs/record-playback/). These explanatory
pages may evolve; qualification must record the pinned comparison version.

## Required behaviour

- Each listener declares TCP or TLS, framing and text/binary representation.
  Support `LINE`, `DELIMITER`, `LENGTH_PREFIX_2B`, `LENGTH_PREFIX_4B`,
  `FIXED_LENGTH` and `STX_ETX`; write-only operation is explicit. No byte
  guessing, highest-priority-mapping framing or protocol downgrade.
  Preserve pipelined request/response order on each connection. Honour configured
  frame, connection and idle limits; use configured TLS identity/trust and client
  authentication when selected. Listener and configuration readback prove readiness.
- Keep native **higher-number-first priority**. Equal priorities select the
  last-created matching mapping; updates preserve creation order. Persist that
  order and restore it on restart. This intentionally differs from WireMock's
  [numeric priority convention](https://wiremock.org/docs/stubbing/).
- Match text equality/contains/regex, exact bytes, byte length, JSON/XML structure,
  JSONPath and XPath. Unknown operators and invalid expressions fail publication.
  Explicit unmatched outcomes replace built-in echo/JSON/`OK` catch-all stubs.
  Authors may declare an ordinary catch-all mapping themselves.
- Templates use [common/templating](../../common/templating/src/main/java/io/pockethive/templating/TemplateRenderer.java)
  through one TCP context adapter. Migrate old expressions explicitly; do not
  try multiple engines or emit unresolved expressions as successful responses.
  Support static text, binary and file responses as well as dynamic templates.
  Binary encoding occurs at the transport boundary, not through implicit text
  conversion. Payload extraction has one implementation shared by its consumers.
- One scenario-state owner atomically selects the state-dependent mapping and
  commits its transition. The canonical initial state is `Started`; initialize
  newly declared scenarios during mapping installation, never through inspection.
  Scenario reset restores that state. State, variables and reset semantics agree
  across request handling and admin reads. Reset persistence must survive restart.
- Delays use scheduled writes, never blocking event-loop sleeps. Connection
  reset produces a verified TCP reset; empty response sends no payload and keeps
  the connection open until its configured deadline. Malformed/random responses
  preserve their declared bytes. EOF and reset remain distinguishable.
- Explicit upstream proxying preserves request/response bytes and framing,
  handles TCP/TLS and configured certificate verification, and has connection,
  response and buffer limits. Failure produces transport evidence; it never
  substitutes a successful-looking response or another target.
- Recording captures completed real upstream exchanges. Replay must work with
  the upstream unavailable. Recording exports an **authoring candidate** for
  the canonical Scenario Manager publication path; it never writes active
  mappings or becomes another definition owner.
- One bounded journal records actual traffic, mapping identity, matched status,
  timing and completed transport outcome for text, binary, fault and proxy paths.
  Exact/at-least/at-most/zero verification queries that journal retroactively,
  using the canonical matcher. Retention loss is reported; incomplete history
  cannot prove exact, at-most or zero counts. Listing, filtering, export and reset
  consume the same records.
- Headless mapping CRUD/import/export uses the SUT authoring/apply lifecycle.
  Invalid input or failed persistence leaves the active configuration unchanged.
  Reset operations distinguish journal, scenario state and applied mappings;
  their completion requires observed effects and follows the shared-SUT rules.
  Mapping restoration reapplies the pinned base mapping set without implicitly
  clearing request history or resetting surviving scenarios.

## Evidence and qualification matrix

All rows are required. **Pending** means source inspection found no qualifying
execution evidence; a partial implementation does not satisfy the target.

| ID / workflow | Required qualification | Observed implementation at audit | Gate |
|---|---|---|---|
| T1 Headless definitions | CRUD/import/export round-trip; duplicate/conflicting IDs; failed publication leaves prior configuration active; restart preserves it. | [Registry](../src/main/java/io/pockethive/tcpmock/service/MessageTypeRegistry.java) silently overwrites IDs and seeds defaults. [Loader](../src/main/java/io/pockethive/tcpmock/service/FileBasedMappingLoader.java) reads `/app/mappings`, writes `/app/data/mappings`, logs failures and continues. | Pending |
| T2 Matching/order | Positive/negative corpus for every matcher, including structured JSON/XML; real JSONPath/XPath; overlapping priorities and stable ties after edits/restart. | [Matcher](../src/main/java/io/pockethive/tcpmock/util/AdvancedRequestMatcher.java) uses dot/index traversal and regex XML tags; unknown criteria can match. Registry ties lack defined ordering. | Pending |
| T3 Templates | Static text/binary/file responses; shared renderer helpers/context; missing/invalid expression errors; binary round-trip; explicit migration corpus. | [Enhanced engine](../src/main/java/io/pockethive/tcpmock/service/EnhancedTemplateEngine.java) leaves unknown expressions unchanged and duplicates payload extraction; advertised `eval`/`randInt` helpers are absent there. | Pending |
| T4 Stateful flows | Concurrent state-dependent requests; independent scenarios; one/reset-all consistency; reset followed by restart. | [StateManager](../src/main/java/io/pockethive/tcpmock/service/StateManager.java) caches mutable metadata around [ScenarioManager](../src/main/java/io/pockethive/tcpmock/service/ScenarioManager.java); reset-all clears memory without persisting. Match/check/update is not atomic. | Pending |
| T5 Framing/bytes | Fragmented/coalesced/pipelined frames and ordered responses, including delays; exact delimiters/lengths; multiple connections; size limits; write-only path. | [Protocol detection](../src/main/java/io/pockethive/tcpmock/handler/ProtocolDetectionHandler.java) guesses framing and defaults fixed length. [Binary handler](../src/main/java/io/pockethive/tcpmock/handler/BinaryMessageHandler.java) converts bytes to hex strings. | Pending |
| T6 TLS | Configured identity/trust, hostname verification upstream, client certificates when configured; rejected handshakes never become plaintext. | [Server](../src/main/java/io/pockethive/tcpmock/core/TcpMockServer.java) generates a self-signed certificate instead of loading the declared identity. | Pending |
| T7 Delays/faults | Timing bounds under concurrency; peer-observed reset versus EOF; empty/malformed/random bytes; cancellation. | Binary handler sleeps on the event loop. [Fault handler](../src/main/java/io/pockethive/tcpmock/handler/FaultInjectionHandler.java) implements reset as ordinary `close()`. | Pending |
| T8 Journal/verification | Actual completed outcomes; unmatched identity; all transport paths; retroactive count queries; retention/reset boundaries. | [Text handler](../src/main/java/io/pockethive/tcpmock/handler/UnifiedTcpRequestHandler.java) infers unmatched status from response text. [Verification](../src/main/java/io/pockethive/tcpmock/service/RequestVerificationService.java) counts registered expectations prospectively and retains an unbounded request list. | Pending |
| T9 Proxy | Framed TCP/TLS round-trip; binary identity; slow/refused/disconnected upstream; bounded buffers and connection cleanup. | [Proxy](../src/main/java/io/pockethive/tcpmock/handler/TcpProxyHandler.java) forwards UTF-8 strings over plain TCP and writes synthetic error text. | Pending |
| T10 Record/replay | Record a real exchange, publish its candidate, disable upstream, reproduce bytes; failed/incomplete capture never publishes a stub. | [RecordingMode](../src/main/java/io/pockethive/tcpmock/service/RecordingMode.java) holds a flag/counter; text-handler records precede the real upstream result. | Pending |
| T11 SUT isolation/recovery | Two swarms share one mock set; another SUT remains isolated; detach preserves state; failed start/reset/restart stays explicit. | Current mocks are stack services; per-SUT ownership is proposed in the linked SUT specification. | Pending |
| T12 Capacity | Configured maxima and maximum-plus-one failures; bounded queues/journal/export; 24-hour mixed-traffic soak and recovery. | [RequestStore](../src/main/java/io/pockethive/tcpmock/service/RequestStore.java) caps two queues at 1,000 entries; no tracked evidence establishes published throughput/coverage claims. | Pending |

## Ownership cleanup before implementation

| Responsibility | Required single owner / cleanup |
|---|---|
| Mapping contract and publication | One canonical TCP mapping schema/type, parser and ordered registry. Remove active compatibility conversions around [StubMapping](../src/main/java/io/pockethive/tcpmock/model/StubMapping.java); import cannot silently lose semantics. Scenario Manager remains authoring authority. |
| Matching/extraction | One matcher and extraction boundary. Retire unused [AdvancedMatcher](../src/main/java/io/pockethive/tcpmock/util/AdvancedMatcher.java); remove copied JSON/XML algorithms. |
| Templates | Shared `common/templating`; retire both service-local engines, including [AdvancedTemplateEngine](../src/main/java/io/pockethive/tcpmock/service/AdvancedTemplateEngine.java). TCP adapter supplies context only. |
| Runtime state/reset | One state repository and transition/reset application service. Controllers delegate; read projections cannot initialise or mutate state. |
| Journal/counts | Consolidate top-level `RequestStore`, nested `ScenarioManager.RequestStore` and verification's request/count stores. One append path supplies read-only queries. |
| Transport/fault/proxy | One action executor; text/binary handlers decode and dispatch. Remove competing [FaultInjector](../src/main/java/io/pockethive/tcpmock/util/FaultInjector.java) semantics and synthetic proxy shortcuts. |
| Files/settings | One resolved root and publication adapter; no load/save root divergence or browser-local settings authority. |

Define executable schemas, errors, reset postconditions and ownership headers
before runtime edits. Extract affected responsibilities from mixed classes.
Required protocol safety must not introduce unrelated configuration strictness.

## Independent acceptance suite and release gate

1. **Contract and component tests:** canonical invalid/valid fixtures, matcher
   and template corpus, state concurrency, atomic publication and reset recovery.
   In-process channel tests cover fragmentation and byte-level faults.
2. **Black-box workflows:** use supported PocketHive admin ingress and published
   SUT endpoints. Observe actual peer traffic; `/api/test`, UI state and handler
   return values cannot prove wire behaviour. Equivalent HTTP/TCP fixtures compare
   the workflow outcomes, accounting for the declared priority convention.
3. **Recovery and isolation:** exercise every T1–T11 failure with concurrent
   clients, process restart and two SUTs. Record configuration revision, runtime
   identity and actual completion evidence.
4. **Capacity:** record versions, topology, payloads, configured limits and
   latency/error budgets before execution. Test limits and overload, then a
   24-hour mixed text/binary/TLS/proxy soak with resets and reconnects. Require
   bounded memory, no leaked connections, correct counts and declared budgets.

At audit, [UiAuthHeaderTest](../src/test/java/io/pockethive/tcpmock/ui/UiAuthHeaderTest.java)
contains two static JavaScript checks. Existing
[E2E features](../../e2e-tests/src/test/resources/features/swarm-lifecycle.feature)
exercise delayed-response failure and journal presence, not equivalence. Their
[TCP URL default](../../e2e-tests/src/main/java/io/pockethive/e2e/config/EnvironmentConfig.java)
uses a direct service port and must not become the new suite's entrypoint.
These sources establish neither “85% coverage” nor “100% parity”.

Release requires passing evidence for **T1–T12** against the implemented
contracts. Capability advertising and README claims must reflect that evidence;
pending rows cannot be counted as complete. This documentation update proves no
runtime gate.

Standalone UI, WireMock SDK/import compatibility, GraphQL, WebSocket, gRPC,
distributed recording and automatic pattern suggestions are separate future
work. They are not substitutes for the required TCP workflows.
