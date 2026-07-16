# Managed Datasets Operator UI Design Specification

Status: in progress — implementation-grade planning baseline; implementation
and qualification evidence pending

Decision target: PocketHive Managed Datasets read-only operator experience

Last updated: 2026-07-16

Normative architecture parent:
[Managed Test Data Architecture and Lifecycle Specification](managed-test-data-lifecycle-generic-spec.md)

Assurance companion:
[Managed Test Data Assurance Strategy](managed-test-data-assurance-strategy.md)

Visual reference:
[Managed Datasets wireframes](managed-datasets-wireframes/README.md)

This document turns the visual reference into an actionable product and
engineering contract. It specifies where every displayed fact comes from, what
it means, when it is current, how every state behaves, and how the feature is
implemented and accepted. It does not claim that the current repository
implements the feature.

## 1. Decision and scope

PocketHive shall deliver a read-only Managed Datasets operator experience in
the active `ui-v2` application. The experience shall contain:

1. an authorised cross-swarm Dataset inventory;
2. a linkable Dataset-selection detail shell;
3. Overview, Fitness, Supply and lifecycle, Consumers, and Evidence views;
4. a Dataset-dependencies section in the existing Swarm Inspector; and
5. the existing bounded Runtime Inspector, composed rather than recreated.

The page is an operational surface, not a data browser and not a seed control.
It answers:

- Which exact Dataset selections are healthy, warming, degraded, unsafe, or
  unavailable?
- Why is a selection in that state?
- Which declared use and Fitness Contract produced the decision?
- What real supply, refresh, validation, distribution, and consumer work is in
  progress?
- Which swarms are affected now, and what is each swarm allowed to do?
- What product-owned evidence proves or fails to prove sourcing, persistence,
  activation, readiness, and use?

The MVP UI shall not expose Dataset record values, decrypted material,
credentials, secrets, provider bodies, storage identifiers, raw database or
Redis browsing, seed/refresh/start/stop/delete controls, or write-capable MCP
operations.

### 1.1 Normative language and requirement IDs

`Shall`, `must`, and `required` are normative. `Should` is a preferred design
that may be replaced only by a reviewed equivalent satisfying the same
acceptance criteria.

Requirements in this document use `DSUI-<area>-<number>` identifiers. A release
that includes the Managed Datasets UI shall satisfy every applicable `DSUI`
requirement and the parent architecture's acceptance criteria. Wireframe copy
or screenshots cannot override this specification.

### 1.2 Current implementation truth

At this specification revision:

- `docs/inProgress/managed-datasets-wireframes/` is a standalone visual
  reference containing fictional layout data;
- production `ui-v2` has no Dataset route;
- there is no `common/dataset-contracts` module;
- Orchestrator has no Managed Dataset module or Dataset read API;
- current auth contracts have no Dataset-specific resource scope; and
- PocketHive MCP reports Dataset capability unavailable.

The only production-backed part of the Inspector wireframe is the existing
Runtime Inspector and its Orchestrator runtime-debug APIs. These facts make the
wireframe non-shippable as code, but do not block using it as visual planning
evidence.

## 2. No-dummy-data rule

### 2.1 Runtime prohibition

`DSUI-DATA-001`: Production code shall contain no fallback Dataset rows,
counts, timestamps, revisions, status labels, consumer identities, operation
history, proof facts, runtime logs, queue facts, user identity, environment
facets, or health facts.

If an authoritative response is missing, denied, stale, partial, malformed, or
unavailable, the UI shall show the corresponding honest state from section 15.
It shall never substitute a visually attractive value.

`DSUI-DATA-002`: The production bundle shall not import, copy, fetch, or embed
the wireframe HTML, wireframe JavaScript, screenshots, capture query parameters,
or test fixtures.

`DSUI-DATA-003`: Test fixtures are permitted only in test source, Storybook or
an explicitly non-production developer harness. They shall be labelled as test
fixtures, excluded from release bundles, and created from the canonical schemas.
End-to-end acceptance shall create real test state through supported product
ingress and shall not preload UI-only rows.

### 2.2 Static product copy versus dynamic facts

The following may be static:

- page, tab, column, field, filter, and button labels;
- explanations of formal states and security boundaries;
- client-side mappings from closed enums/reason codes to reviewed product copy;
- accessibility labels; and
- a user preference default such as Runtime Inspector log-tail size.

The following shall always be response-backed or explicitly unavailable:

- all identifiers and names, including environments, Dataset Spaces, Dataset
  aliases, partitions, pools, declared uses, swarms, runs, operations, proofs,
  workers, queues, and exchanges;
- all counts, thresholds, percentages, durations, times, ages, progress,
  revisions, digests, and versions;
- all health, Fitness, lifecycle, decision, freshness, and evidence states;
- all facets, summaries, attention banners, links, and permissions; and
- all Runtime Inspector data, logs, inspection output, and Rabbit observations.

### 2.3 Null and zero

`DSUI-DATA-004`: `null`, an absent property, a rejected request, or an
unavailable observation shall never render as numeric zero, `Ready`, `Pass`, a
green state, or an unqualified dash. Zero is rendered only when the server
reports a present integer value of zero.

## 3. Product and architecture boundary

### 3.1 Component ownership

| Component | Responsibility |
|---|---|
| Scenario Manager | Author Dataset Space, Dataset definition, declared-use, Fitness Contract, policy and source metadata; it is not the operational UI read authority |
| Orchestrator Managed Dataset module | Own durable runtime truth, read projections, status decisions, operation summaries, consumer readiness and proof facts |
| Swarm Controller | Supply per-swarm membership, route, activation and worker-application observations through typed contracts; it is not global Dataset authority |
| `common/dataset-contracts` | Own closed JSON schemas/Java DTOs, enums, reason codes, compatibility tests and generated TypeScript types |
| Orchestrator REST | Authenticate, authorise, bound, compose and return all UI read models |
| `ui-v2` | Render the returned facts and client-only presentation state; never decide readiness or query infrastructure directly |
| PocketHive MCP | Reuse the same product-owned status/proof services for its three read-only tools; it is not called by the browser |
| PostgreSQL | Durable Dataset authority and source of committed read projections |
| Redis/Rabbit/runtime debug | Operational mechanisms or diagnostics; never a browser-side Dataset authority |

`DSUI-ARCH-001`: The browser shall call authenticated Orchestrator APIs only.
It shall not call PostgreSQL, Redis, Rabbit Management, Docker, Dataset worker
ports, source providers, or MCP for Dataset facts.

`DSUI-ARCH-002`: Inventory and detail reads shall use committed aggregate read
models. A request shall not execute a record-table full scan, an N+1 query per
row, or a fan-out call per Dataset or swarm.

`DSUI-ARCH-003`: Admission-critical facts shall be transactionally consistent
with authoritative Dataset mutations. Any asynchronously maintained display
projection shall return its projection revision and lag and shall never replace
the admission authority.

### 3.2 Production UI integration

The implementation shall use:

- routes in `ui-v2/src/App.tsx`;
- the existing `AppShell`, `SideNav`, `Breadcrumbs`, theme, connectivity and
  authentication contexts;
- a typed client at `ui-v2/src/lib/managedDatasetsApi.ts`;
- focused components under `ui-v2/src/pages/datasets/`;
- the existing `SwarmRuntimeInspector` and `runtimeDebugApi.ts`; and
- the active `ui-v2` design tokens and responsive shell behavior.

The implementation shall not modify or add functionality to the archived UI.

### 3.3 Routes

The required linkable routes are:

```text
/datasets
/datasets/:statusScopeId/overview
/datasets/:statusScopeId/fitness
/datasets/:statusScopeId/supply
/datasets/:statusScopeId/consumers
/datasets/:statusScopeId/evidence
/hive/:swarmId
```

`statusScopeId` is an opaque, URL-safe identifier returned by Orchestrator. It
is stable while the logical operational scope exists, but it grants no access
and shall not encode record values or credentials.

Back, forward, reload, direct-link, new-tab, and authorised copy-link behavior
shall work for every route. Tab selection shall be represented by the path, not
ephemeral React state.

### 3.4 Capability gate

`GET /api/datasets/capabilities` shall advertise the exact supported read-model
schema versions and module state.

- If the capability is supported and the principal has Dataset `VIEW`, the
  Datasets navigation item is visible.
- If it is not implemented or disabled, the navigation item is hidden and a
  direct route displays `Managed Datasets is not enabled in this deployment`.
- If the module is implemented but `RECONCILING`, the navigation remains
  available and views display reconciliation state.
- No capability state enables a fixture fallback.

## 4. Canonical semantic model

### 4.1 Operational status scope

One inventory row and one detail root represent one exact operational status
scope:

```text
SUT Environment
  + Dataset Space
  + Dataset alias
  + partition
  + pool
  + declared use
  + stable Supply Policy identity
  + stable Fitness Contract identity
```

The response also returns the exact active versions. A policy or Fitness
Contract version change does not silently reinterpret an old evaluation; it
produces a new evaluation and activation decision under the same logical scope
or a new status scope where identity changes.

`DSUI-SEM-001`: UI copy shall call `partition / pool` by those names. A
variable profile, when present, is a separate `variableProfileId`; it shall not
be conflated with partition or pool.

### 4.2 Independent facts

The following are related but independent and shall remain visually and
contractually distinct:

1. supply and eligible-record condition;
2. current use-specific Fitness evaluation;
3. prior effective `PASS` continuity for an exact activated view;
4. final-materializer application;
5. durable traffic activation;
6. selector application;
7. a particular swarm's start decision;
8. a running consumer's continue/pause decision; and
9. proof facts such as broker acceptance or flow use.

The browser shall not promote one fact from another.

### 4.3 Formal Dataset health

The UI uses the parent specification's closed Dataset health states:

```text
INITIALISING | WARMING | READY | DEGRADED | STARVED | ERROR | AUTH_REQUIRED
```

`READY` means the exact operational status scope meets the parent
specification's supply, Fitness, remaining-validity and distribution conditions.
It is a necessary Dataset admission condition, not a complete decision that an
arbitrary swarm may start.

### 4.4 Fitness result

Fitness is use-specific:

```text
PASS | FAIL | UNKNOWN
```

The inventory shall not show one Dataset-wide Fitness result across multiple
declared uses. Because a row is one status scope, it shows the result for that
row's declared use and exact contract version.

### 4.5 Authoritative decisions

The server returns decisions; the browser never derives them from counts.

```text
DatasetAdmissionCondition = SATISFIED | NOT_SATISFIED | UNKNOWN | RECONCILING
StartDecision             = ALLOW | BLOCK | UNKNOWN
RunningDecision           = CONTINUE | CONTINUE_UNTIL | PAUSE | UNKNOWN
ConsumerImpact            = NONE | CONTINUING_UNTIL | PAUSED | UNKNOWN
```

Every decision contains bounded `reasonCodes`, `evaluatedAt`, `validUntil`, and
the Dataset/binding revision to which it applies.

### 4.6 Supply quantities

For one exact scope:

```text
deficitToMinimum = max(minimumReady - eligibleReady, 0)
deficitToTarget  = max(targetReady - eligibleReady, 0)
```

These derived quantities shall be computed and returned by the server so API,
UI, and MCP use one implementation.

`reservedProvisioning` is capacity durably reserved by live provisioning
operations. `inFlightRequested` is work dispatched but not terminally
accounted. Neither may be inferred from `deficitToTarget`.

`DSUI-SEM-002`: The label `Replenishing` may be used only when a live operation
reports real reserved or in-flight work. Otherwise the UI shall say `Target
gap`. `REPLENISH` is not an MVP operation kind.

### 4.7 Time and horizon semantics

All response times are RFC 3339 UTC instants. The browser may format a relative
age from a server instant and the current clock, but shall retain the absolute
UTC value in accessible detail and shall never extend a server boundary.

For the MVP's shared reusable data, traffic does not consume a record. A generic
`supply coverage at current demand` duration is therefore prohibited.

If a horizon is shown, the API shall return:

```text
horizonKind = ELIGIBLE_VALIDITY | PRIOR_PASS_CONTINUITY | DISTRIBUTION_DEADLINE
endsAt
calculatedAt
reasonCode
```

The UI label shall name the horizon kind. `PRIOR_PASS_CONTINUITY` always belongs
to an exact activated view and may be aggregated only as the earliest boundary
across a returned, authorised consumer set.

## 5. Shared response and field contract

### 5.1 Envelope

Every Dataset read response shall include:

| Field | Type | Rule |
|---|---|---|
| `schemaVersion` | closed string | Exact supported schema identifier; unknown major versions are rejected |
| `observationId` | opaque string | Identifies one server observation |
| `snapshotToken` | opaque string | Pins pagination/detail reads to one authorised snapshot |
| `observedAt` | UTC instant | Time the product observation completed |
| `refreshAfter` | UTC instant | Earliest recommended automatic refresh; `observedAt <= refreshAfter < validUntil` |
| `validUntil` | UTC instant | After this instant, decisions and current-state labels are stale |
| `moduleState` | enum | `READY | RECONCILING | DEGRADED | UNAVAILABLE` |
| `authoritativeRevision` | non-negative integer | Highest authoritative revision represented by the response |
| `projectionRevision` | non-negative integer | Display projection revision |
| `projectionLagMillis` | non-negative integer | Server-calculated lag between authority and display projection |
| `requestId` | opaque string | Correlation for bounded support diagnostics |

`validUntil` is a display and decision freshness boundary, not a record
`usableUntil`. The two shall never be substituted.

### 5.2 Availability wrapper

Any fact that can be absent uses this closed structure:

```text
Fact<T> {
  availability:
    PRESENT | NOT_APPLICABLE | NOT_YET_EVALUATED |
    UNAVAILABLE | REDACTED | UNAUTHORISED
  value: T                  // present only for PRESENT
  reasonCode: string        // required for every non-PRESENT state
  observedAt: UTC instant   // required when an observation exists
}
```

Schemas shall reject `value` for non-`PRESENT` facts and reject missing `value`
for `PRESENT` facts. Arbitrary backend messages are not part of the contract.

### 5.3 Closed and bounded schemas

All schemas shall set `additionalProperties: false` and enforce:

- identifiers/names: 1 to 128 Unicode code points after declared normalization;
- opaque refs/digests: at most 256 ASCII characters;
- reason-code lists: at most 16 unique codes, each at most 64 ASCII characters;
- evidence refs per fact: at most 16;
- assertions per Fitness page: default 50, maximum 100;
- inventory page: default 50, maximum 100;
- operation and consumer pages: default 25, maximum 100; and
- encoded response body: maximum 512 KiB.

The UI shall render returned identifiers as text, never HTML, and shall not use
raw provider or journal text as display copy.

### 5.4 Snapshot and cursor rules

Inventory summary, facets, attention facts, items, and total shall come from one
authorised snapshot. A cursor is bound to:

- principal and authorisation-scope digest;
- normalized filters and sort;
- snapshot token; and
- page size.

An expired or mismatched cursor returns `409 SNAPSHOT_REQUIRED`. The UI retains
the last view as stale, fetches a new root snapshot, resets to page one, and
announces that the data changed. It shall not merge pages from different
snapshots.

Detail tab requests carry the root `snapshotToken`. If it expires, the client
refreshes the detail root and active tab together.

### 5.5 Error contract

All API errors use `pockethive.problem/v1` with a closed `code`, HTTP status,
`requestId`, and optional `retryAfterSeconds`. No stack trace, SQL, provider
message, record value, endpoint, token, or secret is returned.

Required mappings are:

| HTTP | Code | UI behavior |
|---|---|---|
| `401` | `AUTHENTICATION_REQUIRED` | Use existing sign-in/session-expiry flow |
| `403` | `DATASET_VIEW_FORBIDDEN` | Feature-level permission page; reveal no counts |
| `404` | `DATASET_STATUS_SCOPE_NOT_FOUND` | `Not found or no longer accessible`; do not distinguish hidden scope |
| `409` | `SNAPSHOT_REQUIRED` | Refresh coherent snapshot |
| `429` | `DATASET_READ_LIMITED` | Preserve prior facts, show retry time, honor `Retry-After` |
| `503` | `DATASET_RECONCILING` | Show reconciliation; no readiness claim |
| `503` | `DATASET_READ_UNAVAILABLE` | Preserve prior facts as stale until their `validUntil`, then unknown |
| `500` | `DATASET_READ_FAILED` | Bounded error and request ID |

### 5.6 Refresh rules

The REST response is authoritative. MVP refresh uses conditional authenticated
REST; the browser shall not subscribe directly to Rabbit for Dataset facts.

- At most one request per normalized query key is in flight.
- Obsolete requests are aborted.
- While visible and online, refresh occurs at `refreshAfter`, never more often
  than once every five seconds.
- Hidden/offline pages stop automatic refresh.
- Returning to a visible page refreshes immediately when `refreshAfter` has
  passed.
- Manual Refresh performs a real request and preserves the old observation time
  until a response or validated `304` freshness headers arrive.
- `401`, `403`, schema errors, and malformed responses are not automatically
  retried.
- `429` uses `Retry-After`; transient transport/`503` retry uses capped jitter
  without crossing the server's rate limit.

An Orchestrator-authenticated, scope-filtered SSE invalidation stream may be
added later. It may trigger a REST refresh but shall not become state authority.

## 6. REST read surface and durable projections

The controller paths below are relative to Orchestrator `/api`. `ui-v2` calls
them through the existing `/orchestrator/api` reverse-proxy prefix.

| Method and path | Purpose |
|---|---|
| `GET /datasets/capabilities` | Version and availability gate |
| `GET /datasets/status` | Authorised inventory, summary, facets and attention facts |
| `GET /datasets/status/{statusScopeId}` | Detail header and Overview snapshot |
| `GET /datasets/status/{statusScopeId}/fitness-evaluations` | Current/prior evaluations and paged assertions |
| `GET /datasets/status/{statusScopeId}/operations` | Paged provisioning, refresh, validation and deprovision summaries |
| `GET /datasets/status/{statusScopeId}/consumers` | Paged durable binding and current liveness status |
| `POST /datasets/status/{statusScopeId}/proof-queries` | Bounded, read-only proof query using the canonical proof service |
| `GET /swarms/{swarmId}/dataset-dependencies` | Server-composed Dataset dependencies for one swarm/run |

`DSUI-API-001`: Every endpoint shall use canonical contracts from
`common/dataset-contracts` and shall be documented in
`docs/ORCHESTRATOR-REST.md`.

`DSUI-API-002`: `POST .../proof-queries` is a product read operation. Endpoint
authorisation shall require Dataset `VIEW`, not the current generic mutation
permission. A denied query creates no proof, provider call, Dataset mutation,
Rabbit publication, or lifecycle effect; a bounded security audit is allowed.

`DSUI-API-003`: Inventory search and filters are server-side. The browser shall
not fetch all authorised rows and filter sensitive scopes locally.

### 6.1 Inventory query

`GET /datasets/status` accepts only:

| Parameter | Type and behavior |
|---|---|
| `q` | Optional normalized search over authorised Dataset alias and Dataset Space display name; maximum 128 code points |
| `sutEnvironmentId` | Optional exact authorised identifier |
| `datasetSpaceId` | Optional exact authorised identifier |
| `health` | Optional repeated formal health enum |
| `fitness` | Optional repeated `PASS | FAIL | UNKNOWN` |
| `declaredUseId` | Optional exact declared-use identifier |
| `sort` | `ATTENTION | DATASET_ASC | HEALTH | EARLIEST_SAFE_UNTIL`; default `ATTENTION` |
| `cursor` | Optional opaque cursor |
| `limit` | Optional integer, default 50, maximum 100 |

`ATTENTION` ordering is deterministic:

1. `STARVED`, `ERROR`, `AUTH_REQUIRED`;
2. `DEGRADED`;
3. `WARMING`, `INITIALISING`;
4. `READY`;
5. earliest present `safeUntil`;
6. normalized Dataset Space, alias, partition, pool, declared use; and
7. `statusScopeId` as the stable tie-breaker.

### 6.2 Detail and tab queries

The detail root accepts the opaque `statusScopeId` and no alternate natural-key
path. It returns the exact natural scope for display and links.

Fitness, operation, and consumer pages require `snapshotToken`, `cursor`, and
bounded `limit`. Fitness optionally accepts an exact authorised
`bindingSnapshotId` when the operator selects one consumer context. Operations
accept closed `kind` and `state` filters.

The proof request body is closed:

```text
DatasetProofQuery/v1 {
  snapshotToken
  level:
    CONFIGURED | SOURCED | PERSISTED | BROKER_ACCEPTED |
    FINAL_MATERIALIZER_APPLIED | TRAFFIC_ACTIVATED |
    SELECTOR_APPLIED | READY | FLOW_PROVEN
  bindingSnapshotId: Fact<opaque-ref>
  transactionRef: Fact<opaque-ref>
  intervalRef: Fact<opaque-ref>
}
```

`transactionRef` or `intervalRef` is required only for its matching
`FLOW_PROVEN` query. The request contains no record value or free-text search.

### 6.3 Required durable read projections

The Managed Dataset module shall maintain bounded projections equivalent to:

| Projection | Required meaning |
|---|---|
| `dataset_selection_status` | Exact environment/space/Dataset/partition/pool selection; active policy, revision, eligible and policy aggregates, real reservations/in-flight work, source/refresh state and projection lag |
| `dataset_use_status` | One selection plus declared use/binding/Fitness Contract; current evaluation, effective prior PASS, safe horizon and reasons |
| `dataset_consumer_status` | Durable binding snapshot, membership epoch, expected/applied selector/materializer/activation revisions and latest bounded liveness overlay |
| `dataset_operation_summary` | Bounded provisioning/refresh/validation/deprovision cycle summaries and reconciled accounting |
| `dataset_proof_snapshot` | Immutable, bounded, time-bound proof facts, digest and evidence references |

Request-time reads may join a bounded number of these keyed projections. They
shall not count individual records or scan per-record refresh history.

### 6.4 Authorisation model

Before UI implementation, `common/auth-contracts` shall define an exact Dataset
resource selector covering:

```text
SUT Environment / Dataset Space / Dataset alias / partition / pool
```

The stable declared-use status scope is narrower than that resource. A
principal must have `VIEW` for the full Dataset selector and any referenced
swarm before receiving corresponding facts or links.

Authorisation is applied before inventory totals, facets, attention selection,
pagination, consumer counts, and proof generation. Hidden scopes shall affect
none of them.

- A principal with no feature-level `VIEW` receives `403` and no counts.
- An authorised principal with zero visible scopes receives a successful empty
  inventory.
- A specific absent or unauthorised `statusScopeId` returns the same `404`.
- A consumer link is returned only when that swarm is also viewable.

The frontend may hide controls for presentation, but server authorisation is
the only security control.

## 7. Inventory view

### 7.1 Purpose and row identity

The inventory is the cross-swarm operational entry point. It shows one row per
authorised operational status scope, not one row per database table, Redis key,
record, Dataset definition, swarm, or worker.

The first summary label shall be `Authorised Dataset selections`. If the
product later shows definition count, it shall be a separately named fact.

### 7.2 Inventory response

`DatasetInventoryPage/v1` contains the shared envelope plus:

```text
summary
facets
attention
items[]
page { totalPresent, limit, nextCursor }
```

`totalPresent` is a real, authorised count from the same snapshot. If the total
cannot be calculated within its read budget, `page.total` uses `Fact<number>`
with an explicit unavailable reason; the UI then shows page count without a
fake total.

### 7.3 Summary cards

| UI label | Response field | Exact meaning |
|---|---|---|
| Authorised Dataset selections | `summary.authorisedStatusScopeCount` | Distinct visible operational status scopes |
| Admission thresholds met | `summary.readyStatusScopeCount` | Visible scopes whose formal Dataset health is `READY`; not a complete swarm-start decision |
| Needs attention | `summary.attentionStatusScopeCount` | Visible scopes in `DEGRADED | STARVED | ERROR | AUTH_REQUIRED` |
| Warming | `summary.warmingStatusScopeCount` | Visible scopes in `INITIALISING | WARMING` |
| Activated consumer bindings | `summary.activatedBindingCount` | Distinct durable activated bindings in visible scopes |
| Active consumer swarms | `summary.distinctObservedActiveSwarmCount` | Distinct visible swarms with a current live observation; rendered only when present |

The wireframe's `workers applied` summary is removed. Worker-Dataset
acknowledgements, distinct workers, selectors, and final materializers have
different denominators and belong in Distribution and Consumers.

### 7.4 Attention banner

The banner is returned as one `Fact<AttentionSummary>` from the same snapshot:

```text
severity
statusScopeId
health
reasonCodes
affectedActivatedBindingCount
continuableBindingCount
pausedBindingCount
earliestSafeUntil
```

The server selects the first scope using the `ATTENTION` order. The client maps
reason codes to copy. When no attention scope exists, the banner is omitted; it
does not render a success placeholder.

Copy shall distinguish:

- Dataset health is below its admission condition;
- exact existing bindings may continue until an explicit boundary; and
- exact bindings are paused.

It shall not say that hypothetical future activations are blocked. Exact start
decisions appear only for real bindings in Consumers and Inspector.

### 7.5 Inventory row contract

| Visible area | Canonical fields and semantics |
|---|---|
| Dataset | `datasetRef.datasetAlias`, Dataset Space display name, opaque `statusScopeId` link |
| Partition / pool / environment | Exact `partition`, `pool`, `sutEnvironmentId/displayName`; declared use is available in accessible detail and narrow layouts |
| Dataset health | Formal `health.state`, bounded reasons, evaluation revision |
| Fitness | Current evaluation for this exact declared use: result, contract ref/version, evaluated time |
| Eligible / target | `eligibleReady / targetReady`; expanded detail shows minimum, low watermark, target gap, real reserved provisioning and real in-flight requested |
| Existing-view continuity | `continuableBindingCount` and earliest present prior-PASS `safeUntil`; no active bindings renders `Not applicable` |
| Consumers | Activated binding count and distinct observed-active swarm count as separately named facts |
| Fitness check | Absolute `evaluatedAt`; relative age is client formatting; next evaluation is accessible detail |

The row object also contains exact descriptor, source-binding, Supply Policy,
Fitness Contract, authoritative, candidate, activated, selector and
final-materializer revisions for detail navigation and accessible explanation.

### 7.6 Eligible supply disclosure

The compact cell shows only `eligibleReady / targetReady` and a state line. Its
hover, focus and tap disclosure shows only present facts:

```text
Eligible
Minimum
Low watermark
Target
Target gap
Reserved provisioning
In-flight requested
```

`Target gap` is always the server-returned deficit. `Provisioning` language is
shown only when a real non-terminal `PROVISION_NEW` operation contributes a
positive reserved or in-flight quantity.

The disclosure is a button, is keyboard operable, exposes `aria-expanded`, can
be pinned on tap/click, closes on Escape, returns focus, and is not the sole
location of safety-critical information.

### 7.7 Search, filters and pagination

Facets are returned from authorised data in the current snapshot. No
environment or state option is hard-coded except closed enum labels.

- Filter/search/sort state is reflected in the URL query.
- A change resets the cursor to page one and aborts the obsolete request.
- Search is submitted after 300 ms of inactivity or Enter, whichever occurs
  first.
- `Clear` removes all filters/search and preserves only the default sort.
- Pagination uses Next/Previous navigation backed by retained cursor history;
  it does not guess page numbers from an absent total.
- The visible result count describes returned items, while the total is shown
  only when present.

## 8. Dataset detail shell and Overview

### 8.1 Detail header

The detail header shows:

- Dataset Space and Dataset alias;
- partition, pool, environment, and declared use;
- descriptor, source-binding, Supply Policy and Fitness Contract versions;
- authoritative and activated revisions; and
- response observation/freshness state.

No item is inferred from the route slug. All visible values come from the
authorised detail response.

### 8.2 Principal decision cards

The first card is `Dataset admission condition` and renders the returned
`DatasetAdmissionCondition`. It explains whether this Dataset scope is a
currently satisfied prerequisite. It does not claim that an arbitrary swarm
can start.

The second card is `Existing consumer impact` and renders the returned
`ConsumerImpact` with affected binding counts and the earliest present
`safeUntil`.

Exact `StartDecision` and `RunningDecision` values are displayed per actual
binding in Consumers and Inspector.

### 8.3 Latest Fitness attempt banner

The banner contains the exact current evaluation ID, state, reason codes,
evaluated time, revision and next evaluation. When a prior effective PASS still
permits exact existing views, it is shown as a separate object with its own
receipt ID and `safeUntil`. The current attempt and prior PASS shall never be
visually collapsed into one verdict.

### 8.4 Overview cards

#### Supply Policy

Display present policy facts:

- eligible ready;
- minimum ready;
- low watermark;
- target ready;
- maximum ready;
- reserved provisioning;
- in-flight requested;
- target gap; and
- current non-terminal provisioning operation, if any.

#### Freshness and validity

Replace generic `data age` and demand coverage with named facts:

- oldest eligible evidence time;
- earliest eligible `usableUntil`;
- latest refresh completion;
- next refresh due;
- eligible records due before the next policy guard; and
- Trusted Time observation/state.

Each missing fact uses its availability state. A shared Dataset has no
consumption-based duration.

#### Distribution

Keep these separate:

- authoritative Dataset revision;
- candidate projection revision;
- exact durably activated revision;
- final materializers applied / required;
- selectors applied / required;
- membership epoch; and
- oldest contributing worker observation.

The UI shall not display a revision range as an activated revision and shall
not show `traffic activated` as passed for a candidate whose required final
materializers have not applied.

#### Consumer impact

Display durable activated binding count, current observed-active swarm count,
continuable count, paused count, and earliest safe boundary as separate facts.
`View consumers` navigates to the Consumers route.

## 9. Fitness view

`DatasetFitnessPage/v1` returns:

```text
statusScopeId
declaredUse
fitnessContract { ref, version, digest }
currentEvaluation
effectivePriorPass
assertions[]
page
```

### 9.1 Current evaluation

The current evaluation contains:

- evaluation ID and input-vector digest;
- evaluated authoritative revision and binding snapshot, where applicable;
- `PASS | FAIL | UNKNOWN`;
- evaluated, next-evaluation and `safeUntil` times as applicable;
- bounded reason codes; and
- the assertion counts by result.

`RUNNING` is an evaluation execution state, not a Fitness result. While running,
the last completed current result and its original freshness remain visible; a
new result is not invented.

### 9.2 Effective prior PASS

`effectivePriorPass` is a `Fact<PriorPassReceipt>`. It contains the exact
contract/input digest, activated revision, applicable binding snapshots,
receipt signature/digest metadata, evaluated time and `safeUntil`.

It is `NOT_APPLICABLE` when there is no activated view and
`NOT_YET_EVALUATED` when no prior pass exists. Reaching `safeUntil` makes it
ineffective; the client does not continue a countdown below zero as permission.

### 9.3 Assertions

Each bounded assertion contains:

- stable assertion ID and reviewed display label key;
- required/optional applicability;
- `PASS | FAIL | UNKNOWN | NOT_APPLICABLE`;
- evidence observation time;
- bounded reason codes;
- opaque evidence refs; and
- no raw record value or arbitrary evidence text.

The browser does not recalculate the overall result. One assertion's success
does not fill missing sibling evidence.

## 10. Supply and lifecycle view

### 10.1 Operation kinds and labels

The MVP displays only actual supported kinds:

| Operation kind | UI label |
|---|---|
| `PROVISION_NEW` | Fill / provisioning |
| `REFRESH` | Refresh |
| `VALIDATE` | Validation |
| `DEPROVISION` | Deprovision |

`REPLENISH` is rejected by the parent MVP and shall not appear as an operation
kind. `Replenishing` is not used as a generic synonym for target deficit.

### 10.2 Operation contract

Every operation contains:

- operation ID, kind, state, attempt and fence/lease reference;
- exact scope, source-binding version and Supply Policy version;
- requested, reserved and accepted quantities applicable to its kind;
- complete discriminated accounting counters;
- created, queued, started, deadline, next-retry and completed timestamps as
  applicable;
- resulting authoritative revision;
- bounded terminal/failure code; and
- an authorised Journal link by correlation reference when available.

States are:

```text
RESERVED | QUEUED | RUNNING | SUCCEEDED | PARTIAL |
FAILED | TIMED_OUT | CANCELLED | UNCERTAIN
```

For upsert operations, terminal accounting shall reconcile:

```text
received = inserted + updated + duplicate + rejected
```

The UI renders the server reconciliation result and all contributing counters;
it shall not show an incomplete accepted/rejected percentage.

### 10.3 Empty and history behavior

No active operation renders `No operation is currently active`, not an idle
spinner. History is keyset-paginated. Refresh and validation entries are
bounded cycle/batch summaries, never one row per record. Operation and Overview
facts use the same operation source and cannot disagree silently.

## 11. Consumers view

One row/card represents one durable consumer binding plus its latest bounded
runtime observation.

### 11.1 Consumer contract

Each consumer contains:

- binding snapshot ID, swarm ID and optional current run ID;
- required/optional status and declared use;
- durable binding state;
- exact `StartDecision` and `RunningDecision` with reasons and validity;
- expected Dataset revision;
- candidate and durably activated revisions;
- final materializers applied / required;
- selectors applied / required;
- lowest reported worker-local eligible count;
- reporting worker count / required worker count;
- latest worker observation and its `validUntil`;
- bounded worker exceptions; and
- an authorised Hive route, when permitted.

`lowest reported worker-local eligible` is shown only when every contributing
worker report declares a comparable full local projection. Otherwise the API
returns the appropriate unavailable reason and the UI shows worker coverage
and exceptions without a false aggregate.

### 11.2 Durable versus live state

Durable activated binding state remains visible through an Orchestrator or
controller restart. Live status is a separate availability-wrapped
observation. A missing or stale controller report means `Observation
unavailable`, not `Inactive` and not a zero worker count.

The endpoint returns its own `validUntil`; the client does not hard-code a 30-
or 60-second worker rule. The server composes that boundary from the applicable
status contract.

### 11.3 Completeness

All authorised consumers are available through pagination. If three of six
are rendered, the UI explicitly says `Showing 3 of 6` only when total six is a
present server fact. `View all` shall navigate to or load the real next page.

## 12. Evidence view

The Evidence view renders only a product-created `DatasetProof/v1` from the
same service used beneath MCP `dataset_prove`.

### 12.1 Proof header

Display:

- proof ID, requested level and verdict;
- exact status scope and optional binding snapshot;
- observed and valid times;
- as-of authoritative revision;
- descriptor, source-binding, Supply Policy and Fitness Contract versions;
- canonical digest; and
- explicit gaps.

### 12.2 Independent facts

The closed facts are:

```text
configured
sourced
persisted
broker-accepted
final-materializer-applied
traffic-activated
selector-applied
ready
flow-proven
```

Each fact has `PASS | FAIL | UNKNOWN | NOT_APPLICABLE`, exact scope/revision or
membership epoch, evidence refs, reason codes, and observation validity.

`Broker accepted` means publisher confirm and no unroutable return. It does not
mean a consumer acknowledged or applied the hint. An authoritative
reconciliation may prove application without a broker fact; the UI keeps both
facts independent.

`Flow proven` identifies the exact transaction or declared interval. When no
such proof was requested or produced, it renders `Not requested` or `Not
proven`, not a decorative unknown pass.

### 12.3 Security boundary

Evidence refs are opaque. The UI shall not dereference raw database rows,
provider responses, record values, tokens, endpoints, payloads, or unrestricted
journal text. Copying a proof copies only the bounded proof ID and canonical
digest.

## 13. Swarm Inspector Dataset dependencies

The Dataset-dependencies section is added above the existing Runtime Inspector
in the real Hive/Inspector route. It consumes one server-composed response:

```text
GET /api/swarms/{swarmId}/dataset-dependencies?runId=<exact-run>
```

The browser shall not join inventory, swarm status, worker status, and binding
facts to produce its own gate.

### 13.1 Response

`SwarmDatasetDependencies/v1` contains the shared envelope plus:

- swarm/run identity and durable binding-plan revision;
- aggregate start and running-traffic decisions;
- one card per frozen Dataset binding; and
- response-level exceptions.

Each binding card contains:

- binding snapshot ID and membership epoch;
- exact Dataset reference and declared use;
- required/optional status;
- central Dataset health and supply counts;
- current use-specific Fitness evaluation;
- effective prior-PASS `safeUntil`;
- expected, candidate and activated revisions;
- selector and final-materializer applied/required counts;
- reporting/required worker coverage;
- lowest comparable worker-local eligible count, when present;
- exact unmet gate reason codes; and
- worker exceptions with role, logical slot, incarnation, revision, state, and
  last report.

The UI clearly labels `Authoritative Dataset` versus `This swarm applied`.

### 13.2 Decisions and navigation

The dependency panel displays exact gates for the named swarm/run. A blocked
start does not imply running traffic has stopped. A running decision of
`CONTINUE_UNTIL` shows the exact prior activated revision and `safeUntil`; at
that instant, current UI state is stale until refreshed, while worker safety is
still enforced locally by the product.

`Open Dataset` navigates to the exact `statusScopeId` only when the caller can
view it.

## 14. Existing Runtime Inspector

The production implementation shall compose
`ui-v2/src/pages/hive/SwarmRuntimeInspector.tsx` and
`ui-v2/src/lib/runtimeDebugApi.ts`. It shall not reproduce the prototype's
runtime list, log timer, or fabricated output.

Runtime summary rules are:

- running workers count only resources whose returned `running` value is true;
- present Rabbit queues count only returned queues whose `present` value is
  true;
- list summaries say `Showing X of Y` when a bounded subset is rendered;
- declared and reported versions remain separate;
- queue/exchange type is shown only when the runtime/ownership-manifest
  contract supplies it; and
- runtime logs, Docker inspection and Rabbit depth are diagnostics, never
  Dataset readiness authority.

## 15. Required state model for every view

`DSUI-STATE-001`: Every route shall implement the states below. A route is not
complete when only its populated success state works.

| State | Trigger | Required behavior |
|---|---|---|
| Feature not enabled | Capability unsupported/disabled | Explain that the deployment has not enabled Managed Datasets; no fixture or retry loop |
| Initial loading | No accepted response yet | Structural skeletons with no numbers, names, status colours, times, or pseudo-values; announce loading once |
| Current | Accepted schema and `now < validUntil` | Render response facts and exact observation time |
| Background refresh | Prior response exists and real request in flight | Keep prior facts and original observation time; show `Updating`; do not reset filters, tab, page, or focus |
| Stale | `now >= validUntil` or server says stale | Retain last-known facts with a prominent stale label; all current decisions render `Unknown until refreshed`; no stale green/readiness claim |
| Partial | One or more facts non-present while envelope remains valid | Render known facts; label each missing fact from its availability/reason; do not collapse whole page unless required identity is missing |
| Reconciling | `moduleState=RECONCILING` | Show bounded reconciliation progress when returned; health/readiness remains unknown and new-admission claims are blocked |
| Degraded module | `moduleState=DEGRADED` | Show returned bounded reasons and per-fact availability; do not infer Dataset health from module health |
| Service unavailable | Network failure or `DATASET_READ_UNAVAILABLE` | Preserve last response until stale; provide real Retry; after stale, facts remain visible as last known but decisions are unknown |
| Rate limited | `429` | Show retry time, keep last facts, disable repeated Refresh until allowed |
| Schema/contract error | Unsupported version, extra property, invalid enum/time/invariant | Reject response; show `Dataset status response is incompatible` plus request ID; never partially trust malformed safety facts |
| Unauthenticated | `401` | Existing session-expiry/login flow; clear Dataset query cache |
| Feature forbidden | Feature-level `403` | Permission message with no totals, facets, rows, or hidden identifiers |
| Authorised scope empty | Successful inventory with zero authorised scopes and no filters | Explain that no Managed Dataset selection is available to this account; no warning styling |
| Filter result empty | Successful filtered page with zero items and a non-empty unfiltered authorised scope | `No selections match these filters` and a working Clear action |
| Detail absent/revoked | Specific-scope `404` | `Not found or no longer accessible`; remove stale detail from cache |
| Snapshot changed | `409 SNAPSHOT_REQUIRED` | Refresh coherent root/page and announce the change; never merge snapshots |

### 15.1 Domain-specific states

| View | Required explicit states |
|---|---|
| Inventory/detail | `INITIALISING`, `WARMING`, `READY`, `DEGRADED`, `STARVED`, `ERROR`, `AUTH_REQUIRED`, plus module reconciliation |
| Fitness | no evaluation yet, evaluation running, current `PASS/FAIL/UNKNOWN`, prior PASS effective, prior PASS expired/not applicable |
| Supply/lifecycle | no active operation; every non-terminal and terminal state; `PARTIAL`, `UNCERTAIN`, and `TIMED_OUT` retain their distinct meanings |
| Consumers | no consumers; durable binding with live observation; live observation stale/unavailable; worker hydrating/missing/different revision; safe boundary reached |
| Evidence | proof not requested; proof running only if the API supports asynchronous generation; complete, partial, unknown, failed, expired, and incompatible proof |
| Swarm dependencies | no Dataset bindings; required and optional bindings; blocked start with running traffic unaffected; continue-until; paused; unknown observation |
| Runtime Inspector | debug not permitted; compute adapter unavailable; resource list empty; Rabbit manifest unavailable; Rabbit observation unavailable; logs empty; logs/inspect error |

`DSUI-STATE-002`: Rabbit manifest unavailable, Rabbit observation unavailable,
and zero returned queues are three different states. None may be rendered as
another.

## 16. Interaction, responsive and accessibility contract

### 16.1 One semantic object model

Desktop, tablet, phone, assistive-technology, and print-friendly presentation
shall consume the same normalized response objects. There shall be no duplicate
hard-coded mobile card dataset.

At widths of 820 CSS pixels and above, inventory may use a semantic table. Below
820 pixels, each row becomes a labelled card retaining every safety-critical
fact and the same detail link. At no supported width may the page require
horizontal scrolling to understand health, Fitness, eligible/minimum/target,
continuity, or observation freshness.

Required verification viewports are:

```text
1920 x 1080
1366 x 768
1024 x 768
390 x 844
```

The feature additionally passes 200% browser zoom at 1280 x 800 and reflows to
320 CSS pixels without loss of information or two-dimensional page scrolling.

### 16.2 Information hierarchy

Status is never communicated by colour alone. Every pill/icon has a text label.
Safety-critical identifiers, reasons, times, thresholds, and decisions wrap;
they are not ellipsized. Opaque digests may be visually shortened only when the
full value is available to keyboard and pointer users and Copy uses the full
returned value.

The implementation uses the PocketHive theme tokens, typography, cards, pills,
buttons, focus style, shell spacing, light/dark modes, and real product icons.
It shall not introduce an independent Dataset design system.

### 16.3 Keyboard and focus

`DSUI-A11Y-001`: All functionality is available by keyboard.

- Routes, rows, cards, tabs, filters, pagination, disclosures, Refresh, Journal,
  Hive and Dataset links use native semantic controls.
- Detail tabs implement the WAI-ARIA tabs keyboard pattern or ordinary route
  links; if tabs are used, arrows, Home and End work and browser navigation
  remains correct.
- Supply disclosure opens by Enter/Space, closes on Escape and returns focus.
- Refresh does not move focus.
- After route navigation, focus moves to the page heading unless navigation
  restores a prior browser-history position.
- Dynamic results and stale/error changes use restrained live-region
  announcements without repeating every polling cycle.

### 16.4 Text and time accessibility

Relative time is supplementary. The accessible name or adjacent detail contains
the absolute UTC time. A ticking relative time shall not update a live region.
Countdowns reaching zero trigger a real refresh and a stale/unknown decision
state; they do not locally authorize or pause traffic.

The UI targets WCAG 2.2 AA for contrast, focus visibility, reflow, target size,
names/roles/values and status messages. Screenshots alone cannot prove this;
automated and manual assistive-technology checks are required.

## 17. Security and privacy contract

`DSUI-SEC-001`: Inventory, summary, facets, detail, consumers and proofs are
authorisation-filtered on the server before aggregation. A frontend filter is
not a security boundary.

`DSUI-SEC-002`: Dataset read responses shall contain only bounded identifiers,
versions, revisions, counts, timestamps, enums, reason codes, digests and opaque
evidence references required by this specification. They shall contain no:

- Dataset record or material value;
- plaintext or ciphertext payload;
- PAN, SAD, credential, token, key, nonce or secret;
- provider/SUT response body or arbitrary exception message;
- provider endpoint, database/table/key name or filesystem path;
- raw Docker inspect environment/mount secret; or
- free-text field that can carry prompt injection or unbounded hostile content.

`DSUI-SEC-003`: Client rendering uses text nodes and framework escaping. It
shall not use `dangerouslySetInnerHTML` for returned content. URL construction
uses router encoding and server-returned opaque identifiers.

`DSUI-SEC-004`: Dataset responses remain in the in-memory query cache only.
They are not persisted to `localStorage`, `sessionStorage`, IndexedDB, a service
worker cache, analytics payloads, error-report breadcrumbs, or browser-exported
debug files. Logout, auth-principal change and Dataset-scope change clear the
cache.

`DSUI-SEC-005`: UI and API denials converge on the same product-side
authorisation decision as MCP and other official ingress. A denied request
causes zero Dataset/provider/Rabbit/lifecycle durable command effects.

Feature-specific permission types/selectors shall be implemented before the
navigation item is enabled. The current deployment/folder/bundle-only auth
model is insufficient.

## 18. Performance and continuous-operation contract

The page is off the measured transaction path. It is nevertheless qualified as
load on the shared Orchestrator boundary.

`DSUI-PERF-001`: A measured traffic transaction performs zero UI, status-read,
proof, PostgreSQL, Redis, Rabbit-management or Orchestrator Dataset API call.

`DSUI-PERF-002`: Inventory uses at most four fixed SQL statements per request;
detail root uses at most four; each paged tab uses at most three. Counts do not
grow with returned row, Dataset, consumer or swarm count. Query plans are
captured against the maximum qualified projection cardinality.

`DSUI-PERF-003`: On the named Docker reference deployment and active release
profile:

- inventory, detail-root and tab API latency are each at most 300 ms p95 and
  1,000 ms p99 for accepted requests;
- a 100-item inventory response and every detail/tab response remain within
  512 KiB encoded;
- first meaningful current/empty/error content renders within 2 seconds p95
  from route navigation on the local reference network; and
- input, filter, disclosure and tab interactions respond within 100 ms when no
  network result is required.

These are UI read SLOs, not a universal remote-browser or production-network
claim.

`DSUI-PERF-004`: Qualification runs at least 20 authenticated UI sessions at
the minimum allowed `refreshAfter`, with inventory, detail, consumers and proof
reads distributed across them, while `Q-MVP-1K-24H` traffic and refresh are
active. The parent specification's throughput, p99 control-plane,
PostgreSQL-pool, Rabbit and bulkhead non-interference gates still pass.

`DSUI-PERF-005`: Status/proof reads use the parent specification's dedicated
bounded read bulkhead. They cannot borrow reserved lifecycle/commit permits,
cause unbounded evidence generation, or queue without a deadline. Overload
returns `429`/`Retry-After` or bounded `503`; it never slows safety work through
an unbounded queue.

`DSUI-PERF-006`: The UI remains usable for 24/7 observation. Poll scheduling is
based on server freshness, pauses when hidden/offline, prevents overlap, uses
jitter across sessions, and preserves original timestamps across failures.
Component restart and recovery states update without a page reload once a real
subsequent response arrives.

## 19. Implementation sequence and exit criteria

The sequence below is normative. A later stage may start in parallel only when
it does not invent or locally redefine an unfinished earlier contract.

### Stage 0 — specification and visual alignment

Work:

- approve this document and parent scope;
- apply the semantic wireframe corrections in section 22;
- freeze `DSUI` requirement IDs and reason-code ownership; and
- add UI requirements to the assurance traceability registry.

Exit:

- every visible dynamic datum has one field path and named authority;
- no unresolved decision formula remains in the browser; and
- design/RST review has no open P0 ambiguity.

### Stage 1 — canonical contracts

Work:

- create `common/dataset-contracts`;
- define closed JSON schemas/Java DTOs/enums/reason codes for every endpoint;
- generate or mechanically verify TypeScript types; and
- publish compatibility and hostile-payload TCK vectors.

Exit:

- Java and TypeScript accept/reject the same canonical vectors;
- all size/cardinality rules are executable; and
- no schema contains record values, secrets or unbounded free text.

### Stage 2 — authorisation and capability

Work:

- add exact Dataset resource selectors and `VIEW` handling to
  `common/auth-contracts`;
- implement capability response and endpoint-specific proof-read permission;
- implement server-side authorised aggregation/enumeration limits; and
- add cross-ingress allow/deny equivalence tests.

Exit:

- unauthorised scopes affect no row, total, facet, banner or consumer count;
- direct hidden/absent IDs are indistinguishable; and
- denied reads cause zero command effects.

### Stage 3 — durable projections and server composition

Work:

- implement the five projections in section 6.3;
- extend worker and Swarm Controller typed Dataset status/aggregation;
- implement transactional revision/freshness semantics and startup
  `RECONCILING`; and
- implement bounded snapshot/cursor lifecycle.

Exit:

- projections reconcile against independent authoritative receipts;
- restart preserves durable facts and makes volatile liveness explicitly
  unavailable until observed again; and
- fixed query-count and projection-lag tests pass.

### Stage 4 — official REST reads

Work:

- implement all section 6 endpoints inside Orchestrator;
- document them in `docs/ORCHESTRATOR-REST.md`;
- map problems, caching/freshness and `Retry-After`; and
- expose the same status/proof service to MCP adapters.

Exit:

- contract, auth, pagination, snapshot, restart, overload and security tests
  pass through official ingress; and
- API and MCP facts agree for the same principal/scope/observation.

### Stage 5 — production inventory

Work:

- add `managedDatasetsApi.ts` and a pinned, free TanStack Query dependency;
- add capability-gated route/navigation/breadcrumbs;
- implement inventory summaries, banner, table/cards, filters, paging,
  disclosures and all section 15 states; and
- add unit, component, keyboard, schema and responsive tests.

Exit:

- production bundle contains no wireframe/test fixture;
- every field is response-backed;
- server-side filtering and honest state transitions work; and
- Firefox verification passes at all required viewports.

### Stage 6 — all detail views

Work:

- implement detail shell and the five linkable routes;
- lazy-load bounded tab pages with shared snapshot rules;
- implement proof query and authorised Journal/Hive links; and
- complete state, accessibility and responsive coverage.

Exit:

- Overview, Fitness, Supply/lifecycle, Consumers and Evidence each pass their
  contract and empty/error/stale cases; and
- browser navigation restores route, query, tab and focus correctly.

### Stage 7 — Swarm Inspector integration

Work:

- implement the server-composed dependency endpoint;
- render Dataset dependencies above the existing Runtime Inspector; and
- retain the existing real runtime/debug clients and permission behavior.

Exit:

- central versus swarm-applied truth and start versus running decisions remain
  separate; and
- runtime diagnostics contain no fabricated output and never drive Dataset
  readiness.

### Stage 8 — qualification and rollout

Work:

- run security, accessibility, Firefox, restart, fault, polling and
  non-interference suites;
- run E2E sourcing through official producer/upsert paths and reconcile every
  view against independent receipts;
- add operational metrics/runbook and feature rollout; and
- attach evidence to every `DSUI` child assertion.

Exit:

- all applicable parent and `DSUI` acceptance requirements pass;
- no P0/P1 unexplained contradiction remains;
- capability is enabled only on qualified builds; and
- operator documentation describes each fact and limitation.

## 20. Acceptance requirements

| ID | Pass condition | Primary oracle/evidence |
|---|---|---|
| `DSUI-DATA-001` | Release runtime contains no fallback/fabricated dynamic fact; every absent authority produces an honest state | release-bundle scan, disabled/unavailable API E2E, field-lineage registry |
| `DSUI-DATA-002` | Production source/bundle has no dependency on wireframe/capture/test fixture assets | import graph, bundle manifest, repository policy test |
| `DSUI-DATA-003` | E2E data is created through official product ingress and fixtures cannot enter release bundles | build profile, E2E setup receipts, bundle scan |
| `DSUI-DATA-004` | Missing/null/denied facts never render as zero/ready/pass/green | schema mutation and UI state tests |
| `DSUI-ARCH-001` | Browser network capture contains only authorised Orchestrator Dataset/runtime endpoints and static UI assets | Firefox network capture, egress policy |
| `DSUI-ARCH-002` | Inventory/detail have fixed query counts and no per-row/per-swarm fan-out | SQL instrumentation, request trace |
| `DSUI-ARCH-003` | Display snapshot identifies authority/projection lag and cannot overrule admission truth | revision-lag fault tests, independent authority query |
| `DSUI-SEM-001` | Partition/pool/declared-use/variable-profile fields are never conflated | contract vectors, copy/component tests |
| `DSUI-SEM-002` | Target gap and real reserved/in-flight provisioning remain distinct; unsupported `REPLENISH` is absent | operation ledger versus UI/API, enum TCK |
| `DSUI-API-001` | Canonical Java/JSON/TypeScript contracts and REST docs agree for every endpoint | cross-language TCK, generated drift check |
| `DSUI-API-002` | Proof query is read-authorised and denied queries create zero command effects | auth oracle, database/provider/Rabbit ledgers |
| `DSUI-API-003` | Search/facets/summary/pagination are authorised server-side and hidden scopes leak no aggregate | multi-principal enumeration suite |
| `DSUI-VIEW-001` | Inventory summary, banner, rows, filters, disclosures and paging each render the exact response field and same-snapshot total | API/UI DOM comparison, independent projection oracle |
| `DSUI-VIEW-002` | Detail header and Overview preserve independent Dataset admission, consumer impact, supply, validity, Fitness and distribution facts | response/DOM trace, inconsistent-revision mutation suite |
| `DSUI-VIEW-003` | Fitness keeps current evaluation, prior PASS and assertions independent and never recalculates verdict client-side | independent Fitness oracle, fact-removal tests |
| `DSUI-VIEW-004` | Operation history uses supported kinds/states and reconciled counters; no target deficit is presented as an operation | operation ledger/API/UI comparison |
| `DSUI-VIEW-005` | Consumers show every authorised binding through pagination and distinguish durable binding from live observation | binding ledger, controller restart/staleness suite |
| `DSUI-VIEW-006` | Evidence reproduces canonical proof facts without promotion, raw values or false broker/flow claims | proof canonicalization and fact-removal suite |
| `DSUI-VIEW-007` | Swarm dependencies are server-composed and preserve central-versus-local and start-versus-running distinctions | official swarm endpoint, worker/binding ledgers, fault matrix |
| `DSUI-VIEW-008` | Existing Runtime Inspector is composed; resources/logs/inspect/Rabbit facts come only from real runtime APIs | runtime API/DOM comparison, permission/error tests |
| `DSUI-STATE-001` | Every section 15 state is reachable, visibly distinct and contains no fabricated value | component state matrix and official-ingress E2E |
| `DSUI-STATE-002` | Rabbit manifest absence, observation absence and observed zero are distinct | runtime adapter fault tests |
| `DSUI-A11Y-001` | All routes/actions work by keyboard with correct focus and names/roles/values | Firefox keyboard run, accessibility tree and screen-reader checks |
| `DSUI-A11Y-002` | Required viewports, 200% zoom and 320-CSS-pixel reflow preserve all safety facts without two-dimensional page scroll | Firefox screenshots/reflow measurements |
| `DSUI-SEC-001` | Authorisation precedes every aggregate and page | independent auth oracle and side-channel enumeration suite |
| `DSUI-SEC-002` | Responses/DOM/logs/telemetry contain none of the prohibited sensitive classes | canary scanner, schema and browser capture |
| `DSUI-SEC-003` | Hostile identifiers/reason inputs render only escaped reviewed text and cannot alter DOM/route | XSS/property tests, CSP report |
| `DSUI-SEC-004` | Dataset response state is not persisted client-side and clears on identity/scope change | browser storage/cache inspection |
| `DSUI-SEC-005` | UI/API/MCP authorisation outcomes agree and denied paths have zero command effects | cross-ingress differential test |
| `DSUI-PERF-001` | Measured transaction threads make zero UI/status/proof/central calls | thread I/O detector, network trace |
| `DSUI-PERF-002` | Fixed SQL/query-plan limits pass at maximum qualified cardinality | DB instrumentation and plans |
| `DSUI-PERF-003` | API, payload, render and local-interaction SLOs pass on the named reference deployment | browser/server performance report |
| `DSUI-PERF-004` | Maximum qualified UI polling does not breach the parent active-traffic non-interference gates | paired Q-MVP run with UI sessions |
| `DSUI-PERF-005` | Status/proof overload remains inside its bulkhead and returns bounded retry results without starving lifecycle work | bulkhead telemetry and saturation fault |
| `DSUI-PERF-006` | 24/7 refresh, hide/offline, restart and recovery behavior preserves honest timestamps and no overlap | virtual/browser clock and component restart soak |

## 21. View-to-authority traceability

| View/fact family | Endpoint | Primary projection/authority | Freshness/consistency oracle |
|---|---|---|---|
| Inventory summary/facets/banner/rows | `GET /datasets/status` | selection + use + bounded consumer aggregates | one observation/snapshot; aggregate recomputation from authorised projection |
| Detail decisions/Overview | `GET /datasets/status/{id}` | selection/use status and activation ledger | snapshot token; independent admission/continuity oracle |
| Fitness | `GET .../fitness-evaluations` | immutable evaluations and current/effective receipt pointers | contract/input digest, evaluated revision, trusted time |
| Supply/lifecycle | `GET .../operations` | operation summaries and source/refresh/validation ledgers | terminal accounting invariant and operation fence |
| Consumers | `GET .../consumers` | durable bindings/activation plus latest liveness overlay | binding ledger versus worker reports and response `validUntil` |
| Evidence | `POST .../proof-queries` | canonical proof service/snapshot | canonical digest and independent fact oracles |
| Swarm Dataset dependencies | `GET /swarms/{id}/dataset-dependencies` | durable binding + Dataset authority + activation + liveness | server decision revision and per-source observation times |
| Runtime resources/logs/inspect | existing runtime-debug endpoints | compute adapter/runtime reconciliation | existing API response and redaction contract |
| Rabbit topology | existing runtime-debug Rabbit endpoint | ownership manifest plus bounded Rabbit observation | manifest/observation availability kept separate |

## 22. Required wireframe semantic amendments

The wireframes remain visual examples, not response fixtures. Before final
design sign-off, all views shall reflect these labels and relationships:

1. `Authorised datasets` becomes `Authorised Dataset selections`.
2. Summary `Ready — New starts allowed` becomes `Admission thresholds met` and
   states that exact swarm start is evaluated in context.
3. `Profile / environment` becomes `Partition / pool / environment`; declared
   use is also visible or disclosed.
4. Supply disclosure separates `Target gap`, `Reserved provisioning`, and
   `In-flight requested`; it does not calculate `Replenishing` from the gap.
5. Detail `Can a new activation use this Dataset?` becomes `Dataset admission
   condition`.
6. `Can running traffic continue?` becomes `Existing consumer impact`; exact
   decisions remain per binding.
7. `Supply coverage at current demand` is removed for shared reusable data and
   replaced only by a named validity/continuity horizon.
8. `Data age` becomes named evidence/refresh/validity times.
9. `Workers applied` is split into final materializer, durable activation and
   selector application facts.
10. Any activated revision range is replaced by one exact activated revision;
    candidate revision remains separate.
11. `Replenishment operation` becomes the real `Fill / provisioning`
    (`PROVISION_NEW`) operation.
12. Operation counters reconcile for their discriminated kind.
13. Consumer summaries state exactly how many are shown and provide real
    pagination.
14. Evidence facts carry proof ID, exact revision/epoch, observation validity
    and independent status; broker acceptance is not acknowledgement.
15. Inspector worker-local supply uses lowest comparable worker value plus
    reporting/required coverage, not an ambiguous swarm-local count.
16. Inspector freshness uses response `validUntil`, not a hard-coded timeout.
17. Runtime resources/queues state `Showing X of Y` when a subset is shown;
    queue/exchange type is omitted unless supported.
18. Prototype-only health, connection, user, tool, navigation, Refresh, Logs and
    Inspect behavior is never copied into production.

Illustrative numbers may remain in planning screenshots solely to exercise
layout. They carry no default, seed, expected, capacity or acceptance meaning.
The production acceptance oracle is the field-lineage and official-ingress
evidence defined here.

## 23. Planning definition of done and RST handoff

This design specification is ready for Rapid Software Testing evaluation when:

- all section 22 wireframe relationships are visually represented across the
  inventory, five detail tabs, Inspector, and responsive variants;
- every dynamic element has a section 21 authority mapping;
- every interaction and adverse state has a requirement and observable oracle;
- every API has a specified closed, bounded request/response shape, permission
  and freshness rule; Stage 1 converts those shapes into executable schemas;
- delivery stages have explicit exits and no hidden production-fixture step;
- `DSUI` IDs are linked into the assurance requirement/evidence graph; and
- remaining questions are recorded as blocking specification defects rather
  than left for developers to guess.

Suggested RST focus areas are data lineage, scope/authorisation corners,
state/time boundaries, cross-view contradictions, restart/liveness overlays,
proof-fact independence, browser cache leakage, and control-plane
non-interference. The assurance companion owns charters, heuristics, debrief and
confidence reporting; this document supplies the product oracles they test.
