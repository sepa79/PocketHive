# Managed Datasets wireframe design QA

Status: in progress — source-level design QA only

## Scope and evidence boundary

This report records source-level semantic, interaction and responsive review of
the Managed Dataset planning wireframes. The normative contract is the
[Managed Datasets Operator UI Design Specification](../managed-datasets-operator-ui-design-spec.md).
The repository-relative visual sources are the existing PocketHive
[`ui-v2` stylesheet](../../../ui-v2/src/styles.css),
[`AppShell`](../../../ui-v2/src/components/AppShell.tsx),
[`TopBar`](../../../ui-v2/src/components/TopBar.tsx),
[`SideNav`](../../../ui-v2/src/components/SideNav.tsx), and
[`SwarmRuntimeInspector`](../../../ui-v2/src/pages/hive/SwarmRuntimeInspector.tsx).

The retained operational PNGs are representative design context only and are
excluded from current acceptance evidence. Historical contact sheets,
comparisons and superseded capture sets have been removed. This report makes
source-level claims for the current change, not production visual-capture
claims.

This is not a production-data, API, authorisation, security, performance or
accessibility qualification. Screenshots cannot establish WCAG conformance.

The current `captures/authoring/` PNGs are deterministic desktop planning
captures of the inventory, Package, Space, Registration and lifecycle-safe
removal surfaces. The inventory capture contains no sample row and explicitly
distinguishes a successful authorised-empty response from API failure. Removal
uses no invented identity and delegates draft-deletability and dependencies to
Scenario Manager.
They were rendered from `authoring.html` at 1440×1040 and visually checked for
clipping and ownership-boundary clarity. They do not change the historical
status of the operational-view captures described below and do not claim
implemented authoring behavior or accessibility conformance.

## Remediation result

| Surface | Source-level result | What changed |
|---|---|---|
| Inventory | Passed | `Dataset health` replaces ambiguous `Supply`; global start-block language was removed; consumer counts no longer conflate workers |
| Responsive inventory | Passed | One semantic table becomes labelled cards below 820 px and two-column cards at tablet width; all eight facts and the row action remain present |
| Detail shell | Passed | Dataset health and eligible supply are separate; tab route is represented in the hash and narrow tabs expose a scrollbar/hint |
| Supply scheduling | Passed at source level | Three mutually exclusive source specimens separate the no-change current state, accepted target increase and target decrease; the decrease stages `READY` to `STANDBY` without deletion and completes only after required consumers apply the revision |
| Supply execution journey | Passed at source level | Ordered steps show deficit reconciliation, canonical control-plane start, fresh readiness, bounded `DATASET_SUPPLY` WorkItem and PostgreSQL-backed receipt; later steps never imply they can bypass an unmet gate |
| Component and state clarity | Passed at source level | Orchestrator, Swarm Controller, RabbitMQ, producer swarm and PostgreSQL responsibilities are named; Dataset health, swarm runtime, producer workload, convergence and durable-store state remain separate |
| Reuse and capacity | Passed at source level | `SHARED` explains no-remove/no-add-back reuse, deferred exclusive/ordered modes fail explicitly, and 50,000 is labelled an unproven qualification target |
| Supply/lifecycle | Passed at source level | All five canonical operation literals are shown with scope, attempt, fence, versions, timestamps and reconciled counters; a read-only `UNCERTAIN` cancellation-intent example retains its reservation and refuses a false `CANCELLED` claim. A conclusive wrong-state specimen keeps transport outcome, canonical `FAILED_WRONG_STATE`, `UPSERT_DATASET`, authorised failure target and `contributesToPrimarySupply=false` distinct. |
| Consumers | Passed | Six complete bindings are reachable with working Previous/Next pagination; no worker/revision/decision detail is hidden at narrower widths |
| Evidence | Passed | A bounded proof-level/exact-target/flow-reference form precedes a canonical `DatasetProof/v1`; `BROKER_ACCEPTED` is limited to one `DATASET_SUPPLY` WorkItem attempt and visibly labelled transport-only; `PERSISTED`/PostgreSQL receipt remains completion authority |
| Inspector | Passed | `Authoritative Dataset` and `This swarm applied` are separate groups with explicit revision, application and worker-coverage facts |
| Adverse states | Passed at source level | Queryable reconciling, forbidden, authorised-empty and incompatible states suppress fabricated current facts; stale/rate-limited states preserve only clearly historical evidence; supply specimens cover healthy-idle, readiness wait, dispatch failure and uncertain commit |
| Keyboard/focus model | Passed at source level | Native controls, tabs with Arrow/Home/End, Escape-returning disclosure, route heading focus and live announcements are implemented |
| Authoring journeys | Passed at source level | Package/Space/registration inventories are operable successful-empty views; package sections cover identity/schema/local contracts/storage requirements/all declared content/review; Space uses only `quotaPolicyRef`; registration replacement and remove semantics are explicit; queryable adverse states cover loading, forbidden, unavailable, validation, revision conflict, dependency block and accepted-command/re-read failure |
| Deterministic source QA | Passed | `qa-check.mjs` passed 18 groups covering both documents' IDs/control names, ARIA/fragment/route references, labels, tab keyboard tokens, architecture/state copy, supply-transport proof, source-result routing, authoring SSOT/journeys/adverse states, package-digest vectors and regression tokens |
| Post-change authoring desktop fidelity | Reviewed | Current `captures/authoring/` images were regenerated at 1440×1040 from current source and inspected; operational responsive/Firefox captures remain a separate implementation/design-QA gate |
| Accessibility conformance | Not claimed | Requires contrast, 200% zoom, 320 px reflow, accessibility-tree, keyboard and screen-reader evidence |

## Contract corrections verified in source

1. Operations use only `PROVISION_NEW`, `REPLACE_RECORD`,
   `REFRESH_MATERIAL`, `VALIDATE_RECORD`, and `DEPROVISION_ENTITY`.
2. Proof fact kind, fact status and overall verdict use distinct closed enums.
   Interactive proof-level changes hide omitted facts but never mutate the
   canonical status of a returned fact.
3. `Not requested` is explanatory UI copy derived from `requestedLevel`, not a
   proof fact status.
4. Inventory cursor consistency is carried by an opaque, authorization-bound
   snapshot token; each row retains its own scoped authoritative revision.
5. Inventory and consumer totals are shown only as present server facts; the
   prototype's consumer pages demonstrate real navigation rather than a dead
   `Showing 3 of 6` label.
6. Dataset admission, per-binding start, running continuity, central authority
   and swarm-local application remain visually and semantically separate.
7. Every rendered proof header uses the returned level's authoritative
   revision and freshness boundary; required direct facts cannot silently
   expire before a broader top-level PASS.
8. `UNCERTAIN` remains non-terminal and has explicit, fenced paths to
   effect-free `TIMED_OUT`/`CANCELLED`, same-operation requeue, or one
   reconciled effect receipt.
9. `swarm-start` and `DATASET_SUPPLY` are not one message: the former uses the
   existing `ph.control` lifecycle path; the latter uses the controller-owned
   WorkItem route only after fresh readiness.
10. Producer swarm `RUNNING` and producer workload `IDLE|EXECUTING|UNCERTAIN`
    are independent facts. PostgreSQL receipt, not Rabbit acknowledgement,
    proves durable completion.
11. Dataset lifecycle reconciliation, scenario timeline and traffic pacing are
    three independent timing systems; `maxMessages` is not Dataset size.
12. Shared Managed Dataset selection removes nothing, so add-back is not
    applicable. The exact 50,000-record profile remains `Not yet proven`.
13. `BROKER_ACCEPTED` follows `CONFIGURED` and precedes source/persistence
    evidence. Its target contains the bounded supply delivery attempt,
    `WORKITEM_SUPPLY` class and durable supply operation reference; its Dataset
    revision is not applicable.
14. Broker acceptance copy explicitly rejects consumer acknowledgement,
    execution, persistence and completion meanings. Only the PostgreSQL-backed
    terminal receipt can prove durable Dataset completion.

## Responsive and readability checks

- The table-to-card transition matches the normative `<820 CSS px` boundary.
- Tablet cards expose Dataset, partition/pool/environment, Dataset health,
  Fitness, eligible/target, continuity, consumers, Fitness check and Details.
- Safety-critical identifiers, reason codes, revisions, times and worker facts
  wrap rather than ellipsize.
- Operational fact text is at least 10 px in this deliberately dense planning
  artifact. Production typography must still pass measured zoom/reflow and
  readability testing; pixel size alone is not an accessibility oracle.
- Evidence, consumer, operation and Inspector structures collapse without
  deleting facts.
- The supply journey becomes one ordered card per row below 1100 px; at 480 px
  the state label wraps below the step content rather than forcing horizontal
  page scrolling.
- Component and state grids become two columns below 1100 px and one column
  below 480 px; reuse/capacity becomes one column below 820 px.
- The responsive supply-definition disclosure is positioned below its trigger
  and constrained to the viewport in source; a current 320 CSS-pixel capture is
  still required.
- Mobile/tablet layouts retain exact tool observation UTC, compact consumer
  decision status, and Fitness evidence age.
- At 480 CSS pixels and below, the tool context and qualification chip switch
  to explicit short labels (`Dataset model`, `Unqualified`) so neither can
  overlap; their full accessible names remain in the DOM.

## Team-review visual evidence to refresh

The existing operational Firefox 140.12.0esr images are historical/reference-
only because they contain retired fixture language. The authoring desktop set
was refreshed from current source with local headless Chromium and visually
inspected. A future production-aligned Firefox run must still render and
inspect the operational and responsive surfaces below:

1. Inventory at 1920×1080, 1366×768, 1024×768, 390×844, 320×844 and
   1366×768 at 200% zoom.
2. Overview, Fitness, Supply/lifecycle, Consumers page 1, Evidence and
   Inspector at 1440×1040 and 390×844.
3. Consumers page 2 at 1440×1040.
4. Evidence at `READY`, with missing `FLOW_PROVEN` reference validation, and
   with an accepted exact opaque `FLOW_PROVEN` reference.
5. Reconciling, stale, rate-limited, forbidden, authorised-empty and
   incompatible states at 1366×768.
6. The inventory in dark and light themes.
7. The primary Dataset supply-definition disclosure opened and focused at
   320×844.
8. The isolated alternate cancellation-intent/`UNCERTAIN` specimen scrolled
   into view, with retained reservation and no false `CANCELLED` claim.
9. Supply specimens A, B and C together, with their mutually exclusive labels,
   accepted-but-not-converged increase, and no-deletion target decrease.
10. The default executing journey and `supplyState=idle`,
    `supplyState=waiting_readiness`, `supplyState=dispatch_failed` and
    `supplyState=commit_uncertain` at desktop and mobile widths.

`00-all-desktop.png`, `00-all-mobile.png`,
`00-responsive-theme-proof.png`, `00-adverse-states.png`, and
`00-interaction-details.png` are the required review index. Future verification
must prove each contact-sheet tile is composed from its named current-source
individual and that no individual is blank, loading, incorrectly routed,
incorrectly scrolled, safety-text-truncated or stale-source. No such refreshed
verification is claimed by this source-only change.

A refreshed visual run would establish planning fidelity only. Manual keyboard traversal,
accessibility-tree/name-role-value inspection, contrast measurement,
screen-reader testing and reduced-motion behavior remain required against the
production implementation before `DSUI-A11Y-001` or `DSUI-A11Y-002` can pass.

## Static checks

The remediation is expected to keep all of the following green:

```text
node --check docs/inProgress/managed-datasets-wireframes/app.js
node docs/inProgress/managed-datasets-wireframes/qa-check.mjs
git diff --check
```

Observed deterministic result on 2026-07-21:

```text
Managed Datasets wireframe QA passed: 18 deterministic source-level groups.
```

Source-level semantic/design-readiness result: **green**. Cross-functional
approval remains pending.

Planning visual-fidelity result for the current authoring desktop source:
**reviewed; operational responsive refresh still required for implementation
sign-off**.

Production accessibility/readiness result: **not claimed by this planning
wireframe**.
