# Managed Datasets Operator UI Design Specification

Status: in progress — Grade 0 planning baseline; `G-TEAM-REVIEW-v1` passed
for internal concept feedback; implementation and qualification evidence pending

Decision target: PocketHive Managed Datasets read-only operator experience

Last updated: 2026-07-17

Normative architecture parent:
[Managed Test Data Architecture and Lifecycle Specification](managed-test-data-lifecycle-generic-spec.md)

Assurance companion:
[Managed Test Data Assurance Strategy](managed-test-data-assurance-strategy.md)

Visual reference:
[Managed Datasets wireframes](managed-datasets-wireframes/README.md)

Team decision brief:
[Managed Test Data Team Decision Brief](managed-test-data-team-review-brief.md)

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
| Orchestrator Managed Dataset module | Own durable Dataset truth, revisions, operation summaries, activation/continuity inputs, read projections and proof facts; it does not own controller observations or compose a whole-swarm decision |
| Swarm Controller | Own per-swarm membership, route, current worker-application/readiness aggregation and DatasetGuard contribution through typed contracts; it is not global Dataset authority and does not replace durable Dataset truth |
| Orchestrator admission service | Compose the exact per-binding `StartDecision` and `RunningDecision` from Managed Dataset truth plus Controller-owned current observations under the canonical admission policy |
| `common/dataset-contracts` | Own closed JSON schemas/Java DTOs, enums, reason codes, compatibility tests and generated TypeScript types |
| Orchestrator REST | Authenticate, authorise, bound and return the application-service read models and composed decisions |
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

Its direct `DatasetCapability/v1` also returns:

```text
runningManifestDigest
qualificationEpoch
principalScopeDigest
observedAt
validUntil
requestId
qualification {
  highestPassedProfile:
    Fact<dataset-dev-synthetic | dataset-qualified-core |
         dataset-qualified-sensitive>
  qualificationEvidenceRef: Fact<opaque-ref>
  qualifiedManifestDigest: Fact<sha256-digest>
  evaluatedAt: Fact<UTC-instant>
}
```

Capability is a decision-bearing authenticated response, not a static asset.
It is served `Cache-Control: no-store`, never returns `304`, and has
`observedAt <= validUntil <= observedAt + 30 seconds`. The client may retain it
only in memory under the exact authenticated-session/principal-scope digest,
running-manifest digest and qualification epoch, using the monotonic freshness
rule in section 4.7. Direct Dataset route entry performs a real revalidation.
Identity/auth epoch, schema, manifest or qualification-epoch change; `401`;
logout; malformed response; or expiry clears the tuple and hides/disables the
feature. Network failure does not preserve a stale qualified profile as
permission.

`runningManifestDigest` and `qualification.qualifiedManifestDigest` identify
the immutable qualified deployment contract. They bind the encryption
profile, provider/build, custody boundary, key-floor trust anchor and rotation
protocol, but explicitly exclude live `CoreDatasetKeyManifest` key IDs,
epoch/digest and active-key state. A conforming routine key-material rotation
therefore leaves both digests and `qualificationEpoch` unchanged. Dynamic
key-manifest and safety-floor epoch/digests remain bounded internal
status/evidence. Rotation may make `moduleState=RECONCILING`; invalid or
mismatched key state makes the module unavailable even though qualification
history remains accurate. Changing any pinned encryption/rotation contract
creates a new running/qualified manifest and requires requalification.

`NOT_YET_EVALUATED` means no profile has passed. The UI displays
`No qualified profile` and never infers a profile from enabled code,
deployment topology, planning fixtures, or a lower milestone. A profile is
`PRESENT` only when the server verifies the minimal signed
`CoreQualificationAttestation/v1` (or the stronger enterprise attestation),
including its exact qualified-manifest, build, image and deployment-contract
digests, against the currently running capability; replays the complete
application-owned qualification subject chain and `CORE_QUALIFICATION`
witness journal to the qualified physical/remote namespace head; and requires
the attestation's exact stable subject key/digest to equal
`latestBySubject`. The result must not be superseded. A valid signature or
matching running digest without that inclusion proof is insufficient.
Otherwise the profile is
`NOT_YET_EVALUATED` or `UNAVAILABLE` with a bounded reason. A present
profile is displayed exactly and links only to its bounded authorised evidence
ref.

The qualification tuple is coherent: `highestPassedProfile` is `PRESENT`
if and only if manifest digest and evaluated time are `PRESENT` for the exact
immutable qualified deployment contract. `qualificationEvidenceRef` may be `PRESENT`,
`UNAUTHORISED`, or `REDACTED`; only `PRESENT` creates a link. When the
profile is non-present, digest and evaluated time use compatible non-present
states, and the evidence ref cannot be `PRESENT`. Any other partial tuple is
schema-invalid and renders no profile.

- In a production/release environment, the navigation item is visible only
  when the capability is supported, the principal has Dataset `VIEW`, and
  `dataset-qualified-core` or `dataset-qualified-sensitive` is present for
  the running manifest.
- An internal development deployment may enable only
  `dataset-dev-synthetic` and must display that exact non-release profile on
  every Dataset view.
- With no passed profile, runtime navigation remains hidden/disabled and a
  direct route displays `Managed Datasets is not qualified for this
  deployment`. The labelled static planning wireframe is a review artifact,
  not a fixture fallback or deployable capability state.
- If it is not implemented or disabled, the navigation item is hidden and a
  direct route displays `Managed Datasets is not enabled in this deployment`.
- If the module is implemented and its qualification tuple is still verified
  but operational Dataset state is `RECONCILING`, navigation remains available
  and views display reconciliation state. If qualification chain/witness
  verification itself is unavailable, the profile is non-present and
  production navigation remains hidden under the rule above.
- No capability state enables a fixture fallback.

### 3.5 Fail-closed qualification-candidate access

Qualification is bootstrapped through a test-only candidate state, not by
pretending a profile already passed. An exact build/image/deployment-contract
manifest may enter `QUALIFICATION_CANDIDATE` with an opaque candidate ID and
digest. General navigation remains hidden and ordinary Dataset `VIEW` is
insufficient. Only a separately authenticated principal with
`dataset:qualification:execute`, an audience-bound short-lived candidate token
and the exact candidate/build/deployment digest may open the hidden
qualification entry and call the same production bundle and APIs. Every view
shows `Qualification candidate — no profile passed`; capability still returns
`highestPassedProfile=NOT_YET_EVALUATED`. This state exposes no fixture or
synthetic fallback and grants no command permission.

Candidate sessions may run the required browser, accessibility, fault and
20-session load evidence against official E2E data created through product
ingress. `dataset-dev-synthetic` remains unable to claim release readiness; a
candidate is not that profile and its artifacts are usable only for the exact
candidate digest. Denied, wrong-audience, wrong-digest, expired or ordinary
users receive the same non-qualified route behavior and no candidate or hidden
scope details.

`ActivateDatasetQualification/v1` is a separately authorised application use
case. It verifies the minimal signed core qualification attestation, its
recomputed pinned qualification-lineage subject key, complete child results,
independent-oracle links and exact build/image/deployment digests; durably
appends/read-backs the full subject chain; then appends/advances/read-backs the
exact `CORE_QUALIFICATION` witness-journal entry. Only with the matching
`MonotonicHeadReceipt` may one transaction record the qualification tuple and
witnessed subject receipt for that same running manifest. Startup and every
capability decision repeat full-chain/journal replay and latest-subject
verification. Missing/reset/unavailable witness, truncated chain/journal,
head mismatch or uncertain half-transition returns qualification
`UNAVAILABLE` and module `RECONCILING`; historical pass metadata never enables
navigation. Only the final database commit enables general navigation;
partial evidence, self-asserted UI state or a new manifest cannot. Candidate
access is disabled after activation and reopens only for a new exact candidate
under the same fail-closed rules.

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

The response also carries a stable opaque `revisionScopeId` for the underlying
Dataset/partition/pool aggregate. `statusScopeId` is the use-specific route
and health identity; `revisionScopeId` is the only namespace in which
`authoritativeRevision` values are comparable. Several status scopes may
therefore share one revision scope, and the UI shall never invent a revision
namespace from a route ID.

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
RunningDecision           = CONTINUE | CONTINUE_UNTIL | PAUSE | UNKNOWN | NOT_APPLICABLE
ConsumerImpact            = NONE | CONTINUING | CONTINUING_UNTIL | PAUSED | MIXED | UNKNOWN
```

Every decision contains bounded `reasonCodes`, `evaluatedAt`, `validUntil`, and
the Dataset/binding revision to which it applies.

For every direct response, envelope `validUntil` is no later than the earliest
`validUntil`, prior-PASS `safeUntil`, credential/material horizon or other
safety boundary that contributes to any rendered current decision. A
`CONTINUE_UNTIL.validUntil` is itself no later than its exact receipt
`safeUntil`. The schema/TCK rejects inverted or later boundaries. As defense in
depth, the browser anchors every nested boundary with the same monotonic clock
as the envelope and expires a decision at the earliest applicable instant,
rendering it `UNKNOWN`/paused even if some surrounding display facts remain
fresh. Equality and one-tick-before/at/after crossing, clock skew and suspend
are executable contract cases.

`RunningDecision` is server-owned and closed:

- `CONTINUE` requires a current authoritative `PASS` for the exact active
  view plus every current send-safety term;
- `CONTINUE_UNTIL` requires a signed prior-`PASS` receipt for that exact
  activated view and current trusted time strictly before its `safeUntil`;
- `PAUSE` applies to current `FAIL`, an unsafe view, or an expired safety
  boundary; and
- `UNKNOWN` applies when the current binding observation or decision cannot be
  established; and
- `NOT_APPLICABLE` applies only when the product knows that the durable
  binding has no current running consumer.

Prior-`PASS` continuity alone can never produce indefinite `CONTINUE`.

Status-scope summaries reuse one server-composed aggregate:

```text
ConsumerImpactSummary/v1 {
  impact: ConsumerImpact
  totalActivatedBindingCount
  decisionApplicableBindingCount
  continueBindingCount
  continueUntilBindingCount
  pausedBindingCount
  unknownDecisionBindingCount
  notApplicableBindingCount
  earliestSafeUntil: Fact<UTC-instant>
}
```

The four outcome counts sum exactly to
`decisionApplicableBindingCount`, and
`decisionApplicableBindingCount + notApplicableBindingCount =
totalActivatedBindingCount`. `CONTINUING`, `CONTINUING_UNTIL`, and
`PAUSED` require exactly their matching count to be non-zero. `MIXED` is
returned when more than one outcome count is non-zero; `UNKNOWN` means all
decision-applicable bindings lack a current decision. `NONE` requires zero
decision-applicable bindings; known no-current-run bindings are counted only as
`notApplicableBindingCount`. The client never merges current PASS continuation
with prior-PASS continuity or derives a count by subtraction.

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

Live policy convergence is returned, not inferred:

```text
SupplyPolicyConvergence/v1 {
  requestedVersion: Fact<policy-version>
  requestedTargetReady: Fact<non-negative-integer>
  candidateVersion: Fact<policy-version>
  candidateTargetReady: Fact<non-negative-integer>
  activeVersion
  activeTargetReady
  effectiveAt: Fact<UTC-instant>
  targetChangeDirection: NONE | INCREASE | DECREASE
  convergenceState: STABLE | FILLING_TO_TARGET | APPLYING_SMALLER_VIEW |
                    BLOCKED | PAUSED | UNKNOWN
  fillCycle: Fact<FillCycleSummary/v1>
  lastReconciledAt: Fact<UTC-instant>
  nextReconcileAt: Fact<UTC-instant>
  schedulingLag: Fact<ISO-8601-duration>
  blockerCodes[]
}
```

`FillCycleSummary/v1` contains cycle ID, policy version, opening reason
`INITIAL_DEMAND | LOW_WATERMARK | TARGET_INCREASE |
POLICY_REPLACEMENT_CONTINUATION`, frozen cycle target, state
`OPEN | CLOSING | CLOSED`, reserved/accepted/unresolved totals, opened/last-
evaluated/closed times and closed close reason. It contains no record values.
An accepted policy command may populate requested/candidate facts but cannot be
rendered as active or converged. Required workers may acknowledge an inactive
candidate as `PREPARED`, but the UI calls it applied only after PostgreSQL has
committed the authoritative policy/revision and the worker has switched to
that committed revision. A target decrease is complete only after all required
application acknowledgements. The UI labels `STANDBY` as valid reserve and
never calls it deleted.

Target size is labelled `Desired eligible inventory`. It shall not be
described as transaction count, traffic rate, scheduler messages, usage limit
or Redis-list length. The MVP UI is read-only and provides no target-edit
control.

### 4.7 Time and horizon semantics

All response times are RFC 3339 UTC instants. On each accepted same-origin TLS
response the browser records request-start and receipt-time
`performance.now()`, the origin/proxy-authenticated response `Date`, and the
returned `validUntil`. It validates that server date against `observedAt`
within the deployment's frozen maximum clock skew, then conservatively computes
a monotonic local expiry from `max(0, validUntil - responseDate - measured
requestElapsed)`. Subtracting the whole request interval prevents slow
server/network transit from extending freshness. A response becomes stale when either that
monotonic budget is exhausted or a trustworthy wall clock reaches
`validUntil`; a browser-clock rollback can never extend it. Missing/invalid
server date, excessive forward/rollback disagreement, monotonic-clock
discontinuity, or restored persisted state without a fresh anchor makes
current decisions stale/unknown and triggers a real refresh—it never grants a
new green interval.

Relative age is derived from the accepted server date plus monotonic elapsed
time, not repeatedly from a mutable browser wall clock. The UI always retains
the adjacent absolute UTC value in visible or accessible detail. Visibility
pause, suspend/resume and manual clock changes do not reset the anchored
deadline; tests cover rollback, forward jump, suspend and reload.

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

### 4.8 Bounded binding revision distribution

Activation, materializer application and selector application are
binding/membership facts. A status scope with multiple bindings never exposes
one fabricated aggregate candidate, activated revision, membership epoch, or
worker denominator. It returns:

```text
BindingRevisionDistribution/v1 {
  kind: NONE | UNIFORM | MIXED | UNKNOWN
  completeness: COMPLETE | UNAVAILABLE_OVER_LIMIT
  bindingCount
  unknownBindingCount
  buckets[] {
    expectedRevision: Fact<non-negative-integer>
    candidateRevision: Fact<non-negative-integer>
    activatedRevision: Fact<non-negative-integer>
    bindingCount
    finalMaterializers { applied, required }
    selectors { applied, required }
    oldestWorkerObservation: Fact<UTC-instant>
  }
  reasonCodes[]
}
```

`NONE` has zero bindings and no buckets. `UNIFORM` has exactly one bucket
covering every binding and zero unknown bindings. `MIXED` has two or more
bounded buckets and zero unknown bindings. `UNKNOWN` counts unavailable
bindings and names bounded reasons; known buckets may remain visible, but no
missing binding is folded into a denominator. For every variant,
`sum(buckets[].bindingCount) + unknownBindingCount = bindingCount`; every
count is non-negative, no zero-size bucket is serialized, and bucket vectors
are unique. A bucket's worker counts are sums over only its named binding
revision vector and are labelled with that binding count. Membership epoch
remains per binding and appears only in Consumers or Inspector.

At most 32 distinct buckets are returned. If a scope exceeds that limit, the
server returns `kind=UNKNOWN`, `completeness=UNAVAILABLE_OVER_LIMIT`, no partial
buckets, `unknownBindingCount=bindingCount`, and
`DISTRIBUTION_CARDINALITY_LIMIT`; it never silently drops a binding vector or
lets a partial list drive activation/readiness.

## 5. Shared response and field contract

### 5.1 Projection-read envelope and endpoint applicability

Every Dataset projection-read response from inventory, single-scope detail,
Fitness, operations, consumers, or Swarm Dataset dependencies shall include the
direct fields below. The envelope is a shared field set, not an outer payload
wrapper:

| Field | Type | Rule |
|---|---|---|
| `schemaVersion` | closed string | Exact supported schema identifier; unknown major versions are rejected |
| `observationId` | opaque string | Identifies one server observation |
| `snapshotToken` | opaque string | Pins pagination/detail reads to one authorised snapshot |
| `observedAt` | UTC instant | Time the product observation completed |
| `refreshAfter` | UTC instant | Earliest recommended automatic refresh; `observedAt <= refreshAfter < validUntil` |
| `validUntil` | UTC instant | After this instant, decisions and current-state labels are stale |
| `moduleState` | enum | `READY | RECONCILING | DEGRADED | UNAVAILABLE` |
| `moduleStatus` | `ModuleStatus/v1` | Bounded server-owned reasons, progress and remediation; never inferred by the browser |
| `consistencyPoint` | closed union | Exact snapshot namespace and cursor consistency point; see below |
| `projectionRevision` | non-negative integer | Display projection revision |
| `projectionLagMillis` | non-negative integer | Server-calculated lag between authority and display projection |
| `requestId` | opaque string | Correlation for bounded support diagnostics |

`validUntil` is a display and decision freshness boundary, not a record
`usableUntil`. The two shall never be substituted.

`ModuleStatus/v1` is closed:

```text
ModuleStatus/v1 {
  reasonCodes[]                    // max 8 unique closed codes
  reconciliation: Fact<{
    phase: STARTING | REPLAYING_OUTBOX | REBUILDING_PROJECTION |
           VERIFYING_INVARIANTS | WAITING_DEPENDENCY
    completedUnits
    totalUnits
  }>
  remediation: Fact<Remediation/v1>
}
```

When progress is `PRESENT`, `0 <= completedUnits <= totalUnits <= 1,000,000`
and `totalUnits > 0`; otherwise its availability/reason explains why progress
is unavailable. `READY` has no reasons and reconciliation is
`NOT_APPLICABLE`. `RECONCILING` has at least one reason and reconciliation is
`PRESENT` or explicitly `UNAVAILABLE`; `DEGRADED`/`UNAVAILABLE` have bounded
reasons and do not fabricate progress. `remediation` uses section 5.5 and may
be non-present with its own bounded reason.

`consistencyPoint` is a discriminated union:

```text
ConsistencyPoint/v1 =
  StatusScopeConsistencyPoint {
    kind: STATUS_SCOPE_REVISION
    statusScopeId
    revisionScopeId
    authoritativeRevision
  }
  | InventoryConsistencyPoint {
    kind: INVENTORY_SNAPSHOT
    snapshotToken
  }
  | MultiScopeConsistencyPoint {
    kind: MULTI_SCOPE_SNAPSHOT
    snapshotToken
    statusScopes[] {
      statusScopeId
      revisionScopeId
    }
    revisions[] {
      revisionScopeId
      authoritativeRevision
    }
  }
```

`authoritativeRevision` is meaningful only inside one `revisionScopeId`. An
inventory response therefore carries the opaque, authorisation-bound snapshot
token; it is non-comparable and reveals no ordering or cross-scope revision. The
response never publishes a fictitious "highest" revision across independent
Dataset aggregates. Every inventory item still carries its own `statusScopeId`,
`revisionScopeId`, and scoped authoritative revision. The inventory
consistency-point `snapshotToken` is the same value as the envelope
`snapshotToken`, not a second sequence.

`SwarmDatasetDependencies/v1` uses `MULTI_SCOPE_SNAPSHOT`. Its bounded
`statusScopes[]` contains exactly the authorised route scopes represented by
the returned binding cards, sorted by `statusScopeId`. Its bounded
`revisions[]` is deduplicated and sorted by `revisionScopeId`, so two
use-specific cards for one Dataset aggregate share one revision entry. No
aggregate revision is derived. Its consistency-point `snapshotToken` is the
same opaque, authorisation-bound value as the direct response field.

Endpoint applicability is closed:

| Endpoint family | Response contract |
|---|---|
| `GET /datasets/capabilities` | Direct `DatasetCapability/v1`; capability schema/version, module availability, current-manifest qualification tuple, observation time and request ID only |
| Inventory/detail/Fitness/operations/consumers GETs | Direct endpoint object plus the projection-read fields above |
| `GET /swarms/{id}/dataset-dependencies` | Direct object plus the projection-read fields above and `MULTI_SCOPE_SNAPSHOT` |
| `POST .../proof-queries` and `GET .../proofs/{proofId}` | Direct immutable `DatasetProof/v1` from section 6.2; neither is a projection-read envelope and both use the object's original complete required fields |

Schemas shall reject projection-envelope fields on the direct capability or
proof objects unless a later version explicitly adds them. This applicability
table prevents an endpoint adapter from adding a generic wrapper or silently
inventing irrelevant projection state.

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

The hard limit is measured on the uncompressed canonical UTF-8 JSON body;
transport compression never makes an oversized contract valid. Variable
content in any page is limited to 384 KiB so the fixed envelope and closed
failure representation fit below 512 KiB. A paged builder appends only whole
items/assertions in stable keyset order and stops before the next item would
cross that budget, returning the normal cursor, total and completeness facts.
It never truncates a string, fact or item. An unpaged safety-bearing object
that cannot fit is returned wholly `UNAVAILABLE/RESPONSE_SIZE_LIMIT` with its
decision `UNKNOWN`/start `BLOCK`, never as a partial object or transport error.

Additional direct-array bounds are normative:

| Array | Maximum | Completeness behavior |
|---|---:|---|
| binding revision buckets | 32 | Over-limit uses the complete `UNKNOWN` distribution rule in section 4.8; never truncate |
| values in one inventory facet | 64 | That facet is `UNAVAILABLE/FACET_CARDINALITY_LIMIT`; do not return a partial value list |
| inventory attention facts | 50 | Keyset-page with its own total/cursor; summary counts remain snapshot-coherent |
| active-operation summary buckets | 5 kind and 9 state buckets | Closed enum buckets are complete, including explicit zeroes only when schema requires them; never truncate |
| consumer page response-level exceptions and each consumer's worker exceptions | 16 per array; 128 worker-exception entries per response | Each array returns `totalCount` and `COMPLETE|TRUNCATED`; truncation is visibly diagnostic only and never changes the server decision. Pagination stops before the aggregate response budget |
| dependency binding cards | 100 cards; 16 worker exceptions per card; 16 response-level exceptions; 128 worker-exception entries total | The unpaged dependency object is wholly `UNAVAILABLE/DEPENDENCY_CARDINALITY_LIMIT` or `UNAVAILABLE/DEPENDENCY_DIAGNOSTIC_BUDGET_LIMIT`, with totals and start `UNKNOWN/BLOCK`; never omit a required card or silently trim a safety-bearing diagnostic set |
| `MULTI_SCOPE_SNAPSHOT.statusScopes` and `.revisions` | 100 each | Must cover the complete dependency object; over-limit follows the same unavailable dependency state |

Every bounded list carries `totalCount` and `completeness` when truncation is
allowed. Arrays that contribute to a safety decision are complete or wholly
unavailable, never truncated. Cardinality limits are also binding/admission
limits where the product must support an actionable full view.

The schema build runs a generated maximum-object TCK for every response kind.
It combines maximal strings, facts, reason/evidence lists, nested exception
lists and maximum permitted page/card counts, serializes with the production
canonicalizer, and asserts both the 384 KiB variable-content and 512 KiB hard
limits. Correlated aggregate budgets above are part of generation, not merely
runtime guidance. A schema combination for which no closed bounded success or
`UNAVAILABLE` representation fits fails the build.

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

Read-only does not mean a dead end. Every actionable non-success state uses a
bounded server-owned remediation object:

```text
Remediation/v1 {
  recommendedActionCode:
    WAIT_FOR_RECONCILIATION | RETRY_STATUS_READ_AFTER_BOUNDARY |
    REVIEW_FITNESS_EVIDENCE | REVIEW_SOURCE_OPERATION |
    CONTACT_DATASET_OWNER | REQUEST_ACCESS | NO_OPERATOR_ACTION
  actionOwnerRef: Fact<opaque-ref>
  actionOwnerDisplay: Fact<bounded-display-name>
  runbookRef: Fact<opaque-ref>
}
```

The UI maps the closed code to reviewed copy, displays the owner when present,
and exposes an authorised runbook link when present. A non-present owner or
runbook shows its bounded reason. The object never contains an endpoint,
arbitrary backend text, or an embedded mutation.

Scenario Manager's versioned operability-metadata definition is the authority
for owner display/ref and runbook ref; Orchestrator authorises and projects
those fields and composes only the closed action code. For `403`, it may
return generic access guidance only—never a hidden Dataset owner or runbook.
The absent and unauthorised forms of
`DATASET_STATUS_SCOPE_NOT_FOUND` return byte-equivalent problem/remediation
semantics so remediation cannot become a scope oracle.

All API errors use `pockethive.problem/v1` with a closed `code`, HTTP status,
`requestId`, optional `retryAfterSeconds`, and optional
`Remediation/v1`. No stack trace, SQL, provider message, record value,
endpoint, token, or secret is returned.

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
  until a complete new response is accepted.
- `401`, `403`, schema errors, and malformed responses are not automatically
  retried.
- `429` uses `Retry-After`; transient transport/`503` retry uses capped jitter
  without crossing the server's rate limit.

An Orchestrator-authenticated, scope-filtered SSE invalidation stream may be
added later. It may trigger a REST refresh but shall not become state authority.

Decision-bearing projection reads never return `304 Not Modified`: freshness,
nested decisions, module status and remediation are body fields and no parallel
header-merge contract exists. A client may send an `ETag` as an optimization
hint, but Orchestrator returns a complete validated `200` object (or the
applicable closed error) and the browser atomically replaces the old
observation. Static JavaScript, CSS, font and icon assets may use ordinary HTTP
caching. A future decision-read `304` requires a new versioned
contract that freezes validators and every freshness/header merge invariant.

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
| `GET /datasets/status/{statusScopeId}/proofs/{proofId}` | Re-read one retained immutable canonical proof without recomputation |
| `GET /swarms/{swarmId}/dataset-dependencies` | Server-composed Dataset dependencies for one swarm/run |

`DSUI-API-001`: Every endpoint shall use canonical contracts from
`common/dataset-contracts` and shall be documented in
`docs/ORCHESTRATOR-REST.md`.

The proof POST atomically stores and returns its direct immutable
`DatasetProof/v1`. The proof GET requires the same proof-read permission and
authorised status scope and returns that exact stored object, including its
original observation/validity/request fields and canonical digest; it never
reruns a query or creates a newer time. Proof retention is a frozen deployment
bound covering the qualification/evidence window. Absent, expired,
wrong-scope and unauthorised proof IDs return byte-equivalent
`404 DATASET_PROOF_NOT_FOUND` semantics so retrieval is not an oracle.

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

Reviewed presentation presets may group only exact repeated enums:
`Needs attention = DEGRADED|STARVED|ERROR|AUTH_REQUIRED` and
`Warming = WARMING|INITIALISING`. The client expands a preset into repeated
`health` parameters before the server request and URL serialization; the
macro label is never a wire value. An option is shown only when the authorised
facet response contains at least one member.

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
  claimTarget:
    StatusScopeTarget { kind: STATUS_SCOPE }
    | OperationTarget {
        kind: OPERATION
        operationRef: opaque-ref
        operationKind: PROVISION_NEW | REPLACE_RECORD | REFRESH_MATERIAL
      }
    | DeliveryAttemptTarget {
        kind: DELIVERY_ATTEMPT
        deliveryAttemptRef: opaque-ref
        destinationClass: DATASET_HINT
      }
    | BindingTarget { kind: BINDING, bindingSnapshotId: opaque-ref }
  claimContext:
    NoFlowReference { kind: NONE }
    | TransactionReference { kind: TRANSACTION, transactionRef: opaque-ref }
    | IntervalReference { kind: INTERVAL, intervalRef: opaque-ref }
}
```

`STATUS_SCOPE` is required for `CONFIGURED`; `OPERATION` for `SOURCED`
and `PERSISTED`; `DELIVERY_ATTEMPT` for `BROKER_ACCEPTED`; and
`BINDING` from `FINAL_MATERIALIZER_APPLIED` through `FLOW_PROVEN`. The
`SOURCED` and `PERSISTED` are restricted to `PROVISION_NEW`,
`REPLACE_RECORD`, or `REFRESH_MATERIAL`; validation/deprovision is outside the
closed proof target schema. `SOURCED` requires the successful fully accounted
provider/source result; `PERSISTED` additionally applies the parent
specification's positive durable-result and duplicate-match predicates. A
`BROKER_ACCEPTED` target is
restricted to the `DATASET_HINT` destination class. The
client never authors a `Fact<T>` wrapper or observation metadata for an input
reference. `kind: NONE` is required outside `FLOW_PROVEN`. A `FLOW_PROVEN` query
requires exactly one transaction or interval variant. Missing, dual,
mismatched, or extra reference fields fail schema validation. The request
contains no record value or free-text search.

The proof ID and canonical digest bind the complete target and flow-reference
union. The UI never asks the service to choose an ambiguous "latest"
operation, delivery attempt, binding, transaction, or interval.

The response is the canonical closed contract:

```text
DatasetProof/v1 {
  schemaVersion: pockethive.dataset-proof/v1
  requestId
  proofId
  requestedLevel:
    CONFIGURED | SOURCED | PERSISTED | BROKER_ACCEPTED |
    FINAL_MATERIALIZER_APPLIED | TRAFFIC_ACTIVATED |
    SELECTOR_APPLIED | READY | FLOW_PROVEN
  verdict: PASS | FAIL | UNKNOWN
  claimProfileVersion: pockethive.dataset-proof-claim/v1
  requiredFactKinds[]
  claimTarget: normalized DatasetProofQuery/v1 claimTarget
  claimContext: normalized DatasetProofQuery/v1 claimContext
  statusScopeId
  revisionScopeId
  bindingSnapshotId: Fact<opaque-ref>
  observedAt
  validUntil
  authoritativeRevision: Fact<non-negative-integer>
  descriptorVersion
  sourceBindingVersion
  supplyPolicyVersion
  fitnessContractVersion
  canonicalDigest
  gaps[]
  facts[]
}

DatasetProofFact/v1 {
  factKind:
    CONFIGURED | SOURCED | PERSISTED | BROKER_ACCEPTED |
    FINAL_MATERIALIZER_APPLIED | TRAFFIC_ACTIVATED |
    SELECTOR_APPLIED | READY | FLOW_PROVEN
  status: PASS | FAIL | UNKNOWN | NOT_APPLICABLE
  scopeRef
  revisionScopeId
  authoritativeRevision: Fact<non-negative-integer>
  membershipEpoch: Fact<non-negative-integer>
  observedAt
  validUntil
  reasonCodes[]
  evidenceRefs[]
}
```

Only facts at or below the requested proof level are returned. Unrequested
facts are omitted; `NOT_APPLICABLE` means the fact is structurally inapplicable,
not merely unrequested. The UI may derive the display copy `Not requested` only
from `requestedLevel` and the closed proof-level order. It shall not serialize
`NOT_REQUESTED` as a fact status. Overall `verdict` is returned by the proof
service and is never recalculated by the browser.

`requiredFactKinds` is the exact ordered row from the parent specification's
versioned claim matrix. It is not inferred from proof-level order. The UI may
label required versus contextual facts but cannot alter the array. The
proof-contract TCK rejects a missing/duplicate required fact, wrong target
revision or membership epoch, incomplete gap list, or verdict that disagrees
with the frozen matrix. In particular, direct activated/selector claims may
pass for exact activated revision 1841 while a separate READY proof fails for
candidate revision 1842; current READY is context only for a transaction-bound
FLOW_PROVEN claim.

Top-level `authoritativeRevision` is `NOT_APPLICABLE` with
`FACT_PRECEDES_DATASET_REVISION` when `CONFIGURED` or `SOURCED` is proven before
the first Dataset revision. It is never coerced to zero, omitted, or copied
from an unrelated scope. `schemaVersion`, `requestId`,
`claimProfileVersion`, normalized `claimTarget`, normalized
`claimContext`, `statusScopeId`, and `revisionScopeId` are direct proof
fields; `proofId` plus `canonicalDigest` identify the immutable evidence
object.

`claimTarget.kind=BINDING` if and only if `bindingSnapshotId` is
`PRESENT` with the same opaque value. Every other target kind returns
`bindingSnapshotId=NOT_APPLICABLE` with
`CLAIM_TARGET_HAS_NO_BINDING`; adapters and the UI cannot fill it from
unrelated status context.

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
page { returnedItemCount, total: Fact<number>, limit, nextCursor }
```

When `page.total.availability=PRESENT`, its value is a real, authorised count
from the same snapshot. If it cannot be calculated within its read budget,
`page.total` carries an explicit non-present reason and the UI shows
`returnedItemCount` without inventing a total.

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
consumerImpact: ConsumerImpactSummary/v1
remediation: Remediation/v1
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
| Existing-view continuity | Separate `continueBindingCount` and `continueUntilBindingCount` plus earliest present prior-PASS `safeUntil`; no active bindings renders `Not applicable` |
| Consumers | Activated binding count and distinct observed-active swarm count as separately named facts |
| Fitness check | Absolute `evaluatedAt`; relative age is client formatting; next evaluation is accessible detail |

The row object also contains exact descriptor, source-binding, Supply Policy,
Fitness Contract, `revisionScopeId`, authoritative revision, and the bounded
`BindingRevisionDistribution/v1` from section 4.8 for detail navigation and
accessible explanation. It never flattens per-binding candidate, activated,
selector, or final-materializer facts into singular status-scope values.

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
- `revisionScopeId`, authoritative revision, and the bounded activated
  distribution summary; and
- response observation/freshness state.

No item is inferred from the route slug. All visible values come from the
authorised detail response.

### 8.2 Principal decision cards

The first card is `Dataset admission condition` and renders the returned
`DatasetAdmissionCondition`. It explains whether this Dataset scope is a
currently satisfied prerequisite. It does not claim that an arbitrary swarm
can start.

The second card is `Existing consumer impact` and renders the returned
`ConsumerImpactSummary/v1` with affected binding counts and the earliest
present `safeUntil`.

Exact `StartDecision` and `RunningDecision` values are displayed per actual
binding in Consumers and Inspector.

### 8.3 Latest completed Fitness evaluation banner

The banner contains the last completed current evaluation ID/result, reason
codes, evaluated time, revision and next evaluation. A separately boxed active
`currentAttempt`, when present, shows its ID, `QUEUED|RUNNING`, start and
deadline without changing that completed verdict. When a prior effective PASS
still permits exact existing views, it is shown as a third separate object with
its own receipt ID and `safeUntil`. The active attempt, completed current result
and prior PASS shall never be visually collapsed into one verdict.

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
- `ActiveOperationSummary/v1`: total active count plus bounded counts by exact
  operation kind/state and requested/reserved/in-flight totals; and
- an optional server-selected `attentionOperation` labelled `1 of N`, with a
  working link to the paged Operations view.

The attention operation is explicitly non-exhaustive. When present, the server
selects it deterministically by earliest deadline, then creation time, then
opaque operation ID; the browser never chooses a convenient operation. The
summary equations cover every active operation, so concurrent bounded
provision/refresh/validation/deprovision work cannot be hidden behind one card.

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

- authoritative `revisionScopeId` and Dataset revision; and
- `BindingRevisionDistribution/v1` buckets containing candidate/activated
  revision vectors, binding count, final-materializer and selector denominators,
  and oldest contributing worker observation.

The compact summary displays an activated revision only for `UNIFORM`, with
the covered binding count. `MIXED` displays `Mixed` and its bounded buckets;
`UNKNOWN` displays the named unavailable count/reasons. It shall not display
a revision range or one membership epoch as an aggregate. It shall not show
`traffic activated` for a candidate whose own required final materializers
have not applied.

#### Consumer impact

Display total activated binding count, current observed-active swarm count,
current-PASS continue count, prior-PASS continue-until count, paused count,
unknown-decision count, not-applicable count, and earliest safe boundary as
separate facts. `View consumers` navigates to the Consumers route.

## 9. Fitness view

`DatasetFitnessPage/v1` returns:

```text
statusScopeId
declaredUse
fitnessContract { ref, version, digest }
currentEvaluation: Fact<FitnessEvaluation/v1>
currentAttempt
effectivePriorPass
assertions[]
page
```

### 9.1 Current evaluation

When `currentEvaluation=PRESENT`, the completed evaluation contains:

- evaluation ID and input-vector digest;
- evaluated authoritative revision and binding snapshot, where applicable;
- `PASS | FAIL | UNKNOWN`;
- evaluated, next-evaluation and `safeUntil` times as applicable;
- bounded reason codes; and
- the assertion counts by result.

`currentEvaluation=NOT_YET_EVALUATED/NO_COMPLETED_EVALUATION` is the only
representation of the initial no-evaluation state. It contains no invented
verdict, timestamps or assertions and may coexist with a `PRESENT` queued or
running `currentAttempt`. `currentEvaluation=UNAVAILABLE` means the completed
evaluation authority could not be read and forces the displayed current
decision to `UNKNOWN`; it cannot expose a prior verdict as current. If
`currentEvaluation` is not `PRESENT`, its assertion page is empty with an exact
non-present reason. `effectivePriorPass=PRESENT` requires an independently
valid prior-pass receipt and never changes that current-evaluation state.

`currentAttempt` is a separate `Fact<FitnessEvaluationAttempt/v1>` with exact
attempt ID, `QUEUED | RUNNING` state, required `queuedAt`,
`startedAt: Fact<UTC-instant>`, deadline and bounded reason codes. `startedAt`
is `NOT_APPLICABLE/ATTEMPT_NOT_STARTED` while queued and `PRESENT` only for
`RUNNING`. `currentAttempt` is `NOT_APPLICABLE` when no execution is active. `RUNNING` is an
execution state, not a Fitness result. While it is present, the last completed
`currentEvaluation` and its original freshness remain visible; a new result is
not invented. No completed evaluation may be encoded inside `currentAttempt`,
and an active attempt cannot refresh or extend the previous result's
`safeUntil`.

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

The page begins with the exact `SupplyPolicyConvergence/v1` facts from section
4.6. It shows active and requested/candidate policy versions and targets,
effective time, current convergence state, fill-cycle reason/progress,
eligible/reserved/in-flight/standby quantities, last and next reconciliation,
scheduling lag, provider backoff/circuit state, pause reason/time and bounded
blockers. Non-present requested/candidate/fill-cycle facts render their exact
`Fact` reason rather than zero or `Stable`.

The page explicitly distinguishes:

- **Dataset lifecycle reconciliation**, which is PostgreSQL-backed and keeps
  inventory valid independently of scenarios;
- **scenario timeline**, which schedules swarm start/stop/config actions; and
- **traffic pacing**, which controls worker request rate.

Only the first appears as Dataset supply scheduling. The view never describes
`scheduler.maxMessages`, scenario offsets, or `ratePerSec` as Dataset target
size. It also states that all RabbitMQ swarm control events use the existing
PocketHive control plane, while source work uses the WorkItem plane.

### 10.1 Operation kinds and labels

The MVP displays only actual supported kinds:

| Operation kind | UI label |
|---|---|
| `PROVISION_NEW` | Fill / provisioning |
| `REPLACE_RECORD` | Create successor for revoked identity |
| `REFRESH_MATERIAL` | Refresh material |
| `VALIDATE_RECORD` | Validate records |
| `DEPROVISION_ENTITY` | Deprovision entity |

These five literals are the only MVP wire values. Short aliases such as
`REFRESH`, `VALIDATE`, `DEPROVISION`, and unsupported `REPLENISH` are rejected.
`Replenishing` is not used as a generic synonym for target deficit.

`REPLACE_RECORD` copy and accounting shall show that a new successor identity
was inserted and the predecessor became ineligible. It shall not report the
successor as an update to the predecessor or use material-refresh wording.
`REFRESH_MATERIAL` alone preserves the identity while replacing material.

### 10.2 Operation contract

Every operation contains:

- operation ID, kind, state, attempt and fence/lease reference;
- exact scope, source-binding version and Supply Policy version;
- requested, reserved and accepted quantities applicable to its kind;
- `terminalReceipt: Fact<OperationTerminalReceipt/v1>` using the parent's outer
  `ACCOUNTED_RESULT | EFFECT_FREE_NO_RESULT` discriminant. Accounted results
  contain the exact kind counters, `fullyAccounted`, and upsert duplicate-match
  evidence/matched revision when applicable; effect-free results contain the
  terminal precondition/evidence and no zero-filled kind counters;
- created, queued, started, deadline, next-retry and completed timestamps as
  applicable;
- resulting authoritative revision as a `Fact`, non-present until a durable
  state-changing receipt exists;
- `uncertainAt`, `resolvedAt`, bounded reconciliation evidence/actor refs,
  linked terminal receipt ref, and same-operation requeue attempt/fence when
  applicable;
- `cancellation: Fact<CancellationReadModel/v1>` containing
  `REQUESTED | RESOLVED_CANCELLED | EFFECT_ACCOUNTED`, official
  administrative receipt ref, requested/accepted time, bounded reason,
  accepted-from state/operation version/attempt fence, resolution time,
  bounded evidence refs and terminal receipt ref as applicable;
- bounded terminal/failure code; and
- an authorised Journal link by correlation reference when available.

States are:

```text
RESERVED | QUEUED | RUNNING | SUCCEEDED | PARTIAL |
FAILED | TIMED_OUT | CANCELLED | UNCERTAIN
```

`UNCERTAIN` is a non-terminal reconciliation state with reservation retained;
it carries no closed terminal accounting until resolved. `TIMED_OUT` or
`CANCELLED` is terminal only when the provider outcome is conclusively
effect-free; otherwise the server returns `UNCERTAIN`. The UI does not infer
terminality from the label. Resolution history shows either the linked terminal
effect receipt, an effect-free `TIMED_OUT`/`CANCELLED` reconciliation outcome
whose original deadline/cancellation precondition is explicit, or conclusive
no-effect evidence plus the same operation's newer attempt/fence. The UI never
labels a different operation as that retry, and it never offers a terminal
no-effect outcome while a late provider effect remains possible.

The operator UI and Dataset MCP remain read-only and never originate
`CancelSupplyOperation/v1`. `REQUESTED` renders `Cancellation requested —
reconciling`, preserves the operation reservation/progress facts, and is not a
terminal `Cancelled` label. `RESOLVED_CANCELLED` may be shown only with the
accepted intent and conclusive no-provider-effect evidence returned by the
server. `EFFECT_ACCOUNTED` displays the normal typed terminal outcome and says
that cancellation was too late; it never relabels that effect as cancelled.
Pause, timeout, failure, policy change, shutdown and decommission are not
cancellation. Cancelling `DEPROVISION_ENTITY` does not satisfy or advance the
external-cleanup gate.

Terminal accounting is discriminated by operation kind:

```text
UPSERT:       received = inserted + updated + duplicate + rejected
VALIDATION:   checked = valid + invalid + uncertain
DEPROVISION:  outcomes = deprovisioned + autoExpired + ownerHandedOff + uncertain
```

The UI renders the server reconciliation result and all contributing counters;
it shall not show an incomplete percentage or reuse another receipt kind's
counters. A non-terminal operation displays requested/reserved/progress only
and no committed terminal category until its durable receipt exists. The
resulting revision is `PRESENT` exactly when that receipt changed Dataset
state; effect-free/no-change outcomes display the returned
`NOT_APPLICABLE/NO_DATASET_STATE_CHANGE` fact and never invent a revision.
For duplicate-only upsert, the UI may say `Matched existing persisted result`
only when the receipt returns non-empty bounded durable-match evidence and the
exact `matchedAuthoritativeRevision`; otherwise the result is incompatible,
not inferred from `duplicate > 0`. `fullyAccounted` is the server's qualified
source-profile completion fact and is never computed by comparing demand count
with result-row count.

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
A product-known absence of a current run is instead
`RunningDecision=NOT_APPLICABLE`; it is not an unavailable observation.

The endpoint returns its own `validUntil`; the client does not hard-code a 30-
or 60-second worker rule. The server composes that boundary from the applicable
status contract.

### 11.3 Completeness

All authorised consumers are available through pagination. If three of six
are rendered, the UI explicitly says `Showing 3 of 6` only when total six is a
present server fact. The page contains working Previous/Next controls backed by
cursor history. Selecting a consumer reveals its durable binding, exact
start/running decisions, revision vector, worker coverage, observation
validity, bounded exceptions and authorised Hive link without discarding the
current page or tab state.

## 12. Evidence view

The Evidence view renders only a product-created `DatasetProof/v1` from the
same service used beneath MCP `dataset_prove`.

### 12.1 Proof header

Display:

- proof ID, requested level and verdict;
- exact normalized claim target and claim context;
- claim-profile version and ordered required fact kinds;
- exact status scope, revision scope and structurally applicable binding
  snapshot;
- observed and valid times;
- as-of authoritative revision;
- descriptor, source-binding, Supply Policy and Fitness Contract versions;
- canonical digest; and
- explicit gaps.

### 12.2 Independent facts

The closed serialized `factKind` values are:

```text
CONFIGURED
SOURCED
PERSISTED
BROKER_ACCEPTED
FINAL_MATERIALIZER_APPLIED
TRAFFIC_ACTIVATED
SELECTOR_APPLIED
READY
FLOW_PROVEN
```

Each fact has `PASS | FAIL | UNKNOWN | NOT_APPLICABLE`, exact scope/revision or
membership epoch, evidence refs, reason codes, and observation validity.

`Broker accepted` means publisher confirm and no unroutable return. It does not
mean a consumer acknowledged or applied the hint. An authoritative
reconciliation may prove application without a broker fact; the UI keeps both
facts independent.

`Flow proven` identifies the exact transaction or declared interval. When
`requestedLevel` does not include `FLOW_PROVEN`, that fact is omitted and the UI
may render a separate `Not requested for this proof` explanation. When it was
requested but cannot be established, the returned fact and overall verdict are
`UNKNOWN` or `FAIL` with bounded reasons; the UI never promotes either to pass.

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

Its envelope uses the `MULTI_SCOPE_SNAPSHOT` consistency point from section
5.1, with status-scope mappings per returned authorised Dataset binding and
deduplicated revision entries per distinct `revisionScopeId`.

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
| Supply/lifecycle | no active operation; stable policy; requested/candidate policy not yet effective; target increase filling; target decrease applying a smaller view; blocked/paused/unknown convergence; every fill-cycle state and operation non-terminal/terminal state; `PARTIAL`, `UNCERTAIN`, and `TIMED_OUT` retain their distinct meanings |
| Consumers | no consumers; durable binding with live observation; known no-current-run with `RunningDecision=NOT_APPLICABLE`; live observation stale/unavailable with `UNKNOWN`; worker hydrating/missing/different revision; safe boundary reached |
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
| Remediation action/owner/runbook | Inventory/detail/error endpoint carrying `Remediation/v1` | Scenario Manager versioned operability metadata; Orchestrator authorisation and closed action-code composition | metadata version/ref, 403 redaction and byte-equivalent hidden/absent 404 checks |

## 22. Applied wireframe semantic contract

The wireframes remain visual examples, not response fixtures. The current
source applies the labels and relationships below and passed source-level
semantic review for `G-TEAM-REVIEW-v1`. The existing Firefox images predate the
current neutral fixtures and resize specimens and are reference-only. Current-
source visual fidelity is not claimed; it requires deterministic recapture and
inspection. Production design sign-off additionally requires implementation-
backed accessibility, authority and behavior verification:

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
19. Operations use the five canonical kinds in section 10.1 without display
    aliases leaking into serialized values.
20. Proof queries expose requested level and exact optional context; proof
    results use the canonical uppercase fact kinds, closed statuses, returned
    verdict, explicit gaps, versions, scope/revision and validity.
21. Unrequested proof facts are omitted. `Not requested` is derived explanatory
    copy outside the fact list and is never a serialized fact status.
22. Consumer page controls reach every authorised binding and each responsive
    card retains decisions, revision vector, worker coverage and validity.
23. Inventory uses one semantic object model across viewports; the same table
    rows become labelled cards below 820 CSS pixels.
24. Cross-Dataset inventory coherence uses the opaque authorisation-bound
    snapshot token. Only individual status-scope rows display authoritative
    Dataset revisions.

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
