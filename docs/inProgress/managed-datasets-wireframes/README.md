# Managed Datasets responsive planning wireframes

Status: in progress — design-review artifacts only

These standalone wireframes exercise the read-only Managed Dataset operator
experience specified by the lifecycle specification and the normative
[Operator UI Design Specification](../managed-datasets-operator-ui-design-spec.md).
They preserve the PocketHive shell and visual language, but they do not modify
or depend on the production UI runtime and are not production implementation.

The design specification—not the fictional values in this folder—is the
semantic and acceptance authority. Production `ui-v2` obtains every dynamic
fact from authorised Orchestrator read models and renders an honest
unavailable, empty, stale or incompatible state when that authority is absent.
It never imports or falls back to this prototype.

## Included flows

The authoring companion at `authoring.html` adds three contract-aligned,
deterministic planning views:

- `#inventory` — authorised Scenario Manager list/search/filter structure with
  an honest successful-empty response and no invented row or fallback;
- `#package` — portable Dataset package schema and package-local Contract
  authoring, with deployment context excluded;
- `#space` — deployment authority-policy authoring without Dataset schema,
  Contract or storage-selection editing; and
- `#registration` — explicit published-package, active-Space, alias, adapter,
  settings-reference and capability-profile binding; and
- `#remove` — server-decided exact draft deletion versus version retirement,
  with dependency recheck and no force-delete path.

Current desktop review captures live in `captures/authoring/`. They are visual
planning artifacts, not evidence that the APIs or authoring lifecycle are
implemented.

| Authoring capture | Coverage |
|---|---|
| `dataset-authoring-inventory-empty-desktop-1440.png` | Real-data list structure and successful authorised-empty state without sample rows |
| `dataset-authoring-remove-desktop-1440.png` | Exact unreferenced-draft deletion and published/active retirement boundary |
| `dataset-package-authoring-desktop-1440.png` | Record schema, natural key, package-local Contracts and storage compatibility requirements |
| `dataset-space-authoring-desktop-1440.png` | Deployment authority/SUT scope, access policy, classification, quotas and allowed profiles |
| `dataset-registration-authoring-desktop-1440.png` | Exact package/Space binding, alias, explicit adapter, settings reference and capability check |

- `#datasets` — cross-swarm inventory of authorised operational status scopes;
- `#dataset/overview` — exact status-scope admission, continuity, supply,
  distribution and consumer summary;
- `#dataset/fitness` — latest evaluation and prior-PASS continuity;
- `#dataset/supply` — active/requested policy and target convergence, durable
  fill-cycle/reconciliation timing, the mandatory control/readiness/WorkItem/
  commit sequence, component placement, orthogonal runtime states, the three-
  scheduler distinction, shared no-add-back reuse, the unqualified 50,000-
  record target, the five canonical operation kinds and reconciled lifecycle
  accounting;
- `#dataset/consumers` — six complete bindings through working cursor-style
  Previous/Next pagination;
- `#dataset/evidence` — bounded, idempotent proof creation and a canonical
  `DatasetProof/v1` result; and
- `#inspector` — Swarm Inspector with `Authoritative Dataset` and `This swarm
  applied` truth explicitly separated above bounded runtime diagnostics.

Open `index.html` directly or serve the repository root with a static HTTP
server. Add `?capture=1` before the hash to hide the wireframe-only view
switcher. A legacy `?tab=` capture parameter remains supported, but normal
navigation uses the route-like hash above so browser history and tab state stay
aligned. Add the wireframe-only `theme=light` query parameter to initialise the
existing PocketHive light-theme tokens for capture; it introduces no new theme
or production preference behavior. The planning harness also supports
consumer-page, proof-level, proof-validation, supply-state and focus query
controls for a future capture refresh. These controls are prohibited from
production bundles by `DSUI-DATA-002`.

## Contract-sensitive behavior

- Formal Dataset health, eligible supply, use-specific Fitness, per-binding
  start decisions and running-traffic decisions are different facts.
- Inventory never says that all future starts are blocked. Exact start
  decisions appear only on real runtime-resolved consumer bindings and in
  Swarm Inspector; no authored cross-bundle consumer registry exists.
- Operation wire values are exactly `PROVISION_NEW`, `REPLACE_RECORD`,
  `REFRESH_MATERIAL`, `VALIDATE_RECORD`, and `DEPROVISION_ENTITY`.
- Proof fact kinds use uppercase underscore wire values. Fact status is only
  `PASS | FAIL | UNKNOWN | NOT_APPLICABLE`; unrequested facts are omitted and
  explanatory `Not requested` copy is derived from `requestedLevel`.
- The proof form exposes the one fixed exact status-scope, operation,
  delivery-attempt or binding target required by the selected level. Its
  planning fixture offers one opaque transaction reference for `FLOW_PROVEN`;
  the normative product contract supports the closed transaction/interval
  union. It cannot browse values or cause lifecycle work.
- `BROKER_ACCEPTED` is scoped only to one bounded `DATASET_SUPPLY` WorkItem
  delivery attempt and durable operation reference. The UI labels it transport
  evidence only: publisher confirm plus no unroutable return. It never proves
  consumer acknowledgement, source execution, persistence or completion;
  `PERSISTED` and the PostgreSQL terminal receipt remain the durable-result
  authority.
- Operation details must keep transport outcome, canonical
  `SourceResultOutcome`, route action, authorised target and
  `contributesToPrimarySupply` separate. A 2xx/successful TCP exchange with a
  wrong terminal state is not completion; `PENDING`/`UNCERTAIN` show no final
  Dataset route. Failed-target counts never fill the completed Dataset target,
  and raw HTTP/TCP responses are never rendered.
- Supply uses three explicitly mutually exclusive source specimens: the
  current policy with no pending change, an accepted target increase that has
  not converged, and a target decrease that stages surplus records from
  `READY` to `STANDBY` without deleting them. A decrease completes only after
  every required consumer applies the new revision.
- The supply journey first reconciles durable demand; uses the existing
  Orchestrator lifecycle path and `ph.control` to start a stopped producer;
  waits for the matching outcome plus fresh `RUNNING`, input-enabled and route-
  ready state; then publishes bounded metadata-only `DATASET_SUPPLY` through
  the controller-owned WorkItem route. PostgreSQL receipt—not Rabbit
  acknowledgement—proves completion.
- Dataset selection health, producer swarm runtime, producer workload, supply
  operation, policy convergence and durable store are independent visible
  facts. A producer can be `RUNNING` while its workload is `IDLE`.
- The MVP allocation is `SHARED`: local immutable views reuse records without
  removing or adding them back. Exclusive, ordered and consumable behavior is
  deferred and cannot be silently simulated.
- The 50,000-record/55,000-maximum, two-swarm profile is visibly `Not yet
  proven`; the planning fixture makes no support claim.
- Consumer cards retain start/running decisions, revision vector, materializer
  and selector coverage, comparable worker-local supply, observation validity
  and bounded exceptions at every supported width.
- Narrow tool context keeps the exact last-observed UTC instant and run
  reference; consumer decision status and Fitness evidence age are not removed
  at tablet or mobile widths.
- Inventory uses one table/row object model. CSS converts those same rows to
  labelled cards below 820 CSS pixels; there is no second mobile fixture.
- The planning health presets use exact formal row enums. `Needs attention`
  expands to `DEGRADED|STARVED|ERROR|AUTH_REQUIRED` and `Warming` to
  `WARMING|INITIALISING`; production sends repeated server-side `health`
  parameters and never sends a macro literal or filters all scopes locally.
- Tabs expose a visible horizontal scrollbar on narrow layouts, implement
  Arrow/Home/End behavior, and keep the selected view in the URL hash.
- Route navigation moves focus to the destination heading. Refresh and
  pagination preserve the initiating control while live regions announce the
  result.
- Controls whose product route is outside this planning fixture are visibly
  disabled and named as unavailable; they do not masquerade as working links.

## Representative adverse states

Use the `state` query parameter with `#datasets` to exercise bounded adverse
state presentations without inventing operational facts:

```text
?state=reconciling#datasets
?state=stale#datasets
?state=rate_limited#datasets
?state=forbidden#datasets
?state=empty#datasets
?state=incompatible#datasets
```

Reconciling, forbidden, authorised-empty, and incompatible examples suppress
the populated fixture. Stale and rate-limited examples instead retain the
last-known inventory as disabled historical evidence: every row's current
Dataset health, Fitness, continuity, and admission meaning becomes `Unknown
until refreshed`, while prior values remain labelled for diagnosis. The
rate-limited example exposes the exact server-provided `Retry-After` boundary
and keeps refresh disabled until that instant. A forbidden state exposes no
totals or identifiers, and an incompatible response is rejected as a whole.

The Supply route has four additional journey specimens. Each keeps lifecycle,
readiness, WorkItem and durable commit causally ordered:

```text
?supplyState=idle#dataset/supply
?supplyState=waiting_readiness#dataset/supply
?supplyState=dispatch_failed#dataset/supply
?supplyState=commit_uncertain#dataset/supply
```

`waiting_readiness` explicitly says that no WorkItem was published;
`dispatch_failed` retains the same durable operation without claiming
execution; and `commit_uncertain` retains its reservation without a blind
replacement or false success.

## Design boundaries

- PocketHive `ui-v2` shell, theme tokens, typography, cards, pills, tabs,
  focus style and responsive conventions remain the visual source of truth.
- Fictional opaque identifiers, aggregate counts, revisions and timestamps
  exist only to exercise layout. They define no runtime default, seed, capacity
  expectation or acceptance value.
- No Dataset value, restricted real-world data, secret, provider output, direct datastore
  browsing, seed command, refresh command or lifecycle mutation is present.
- Responsive screenshots can demonstrate layout and visible copy only. They
  cannot prove API authority, authorization, keyboard behavior, accessible
  names/roles/values, contrast, reflow, zoom or screen-reader compatibility.

## Capture evidence status

`captures/spec-aligned/` contains a frozen earlier Firefox 140.12.0esr review
set. Those images predate the neutral fixture names, retain retired business-
specific fixture wording, and predate the current supply journey and resize
specimens. They are historical/reference-only and are excluded from current
design evidence. Their contact sheets cover:

| Evidence | Coverage |
|---|---|
| `00-all-desktop.png` | Inventory, Overview, Fitness, Supply/lifecycle, Consumers page 1, Evidence and Inspector at 1440×1040 |
| `00-all-mobile.png` | The same primary surfaces at 390×844 |
| `00-responsive-theme-proof.png` | Inventory at 1920×1080, 1366×768, 1024×768, 320×844 and 200% zoom; dark/light; Consumers page 2; missing and accepted `FLOW_PROVEN` references |
| `00-adverse-states.png` | Reconciling, stale, rate-limited, forbidden, authorised-empty and incompatible states at 1366×768 |
| `00-interaction-details.png` | READY and FLOW proof interactions, the opened 320-pixel supply-definition disclosure, and the scrolled alternate cancellation/`UNCERTAIN` specimen |

The current neutral wireframe source and `qa-check.mjs` result are the concept-
approval artifacts. No capture was refreshed because the approved browser
could not open the local prototype in this environment. A new deterministic
render and visual inspection of every named individual and contact sheet is
required before claiming current-source visual fidelity. The 320-pixel and
200%-zoom reference images intentionally show only the top of vertically
scrollable content.

Planning visual-fidelity result for the current source: **not claimed; capture
refresh required**.

Accessibility sign-off additionally requires automated checks plus manual
keyboard, accessibility-tree and screen-reader verification. No screenshot set
alone establishes WCAG conformance, and this planning result does not pass
`DSUI-A11Y-001`, `DSUI-A11Y-002` or any implementation/release gate.

## Deterministic source checks

Run from the repository root:

```text
node --check docs/inProgress/managed-datasets-wireframes/app.js
node docs/inProgress/managed-datasets-wireframes/qa-check.mjs
git diff --check
```

The wireframe QA script checks duplicate IDs, ARIA ID references, internal
fragment and route targets, tab relationships and keyboard tokens, form/button
names, image alternatives, required architecture/state copy, supply adverse
states and undersized-text regressions. It does not replace rendered visual,
contrast, zoom, accessibility-tree or screen-reader evidence.
