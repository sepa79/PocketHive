# Managed Datasets wireframe design QA

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

The root-level PNGs and `captures/spec-aligned/` set predate the current neutral
fixture names and supply resize specimens. They remain comparison context only;
this report makes source-level claims for the current change, not visual-capture
claims.

This is not a production-data, API, authorisation, security, performance or
accessibility qualification. Screenshots cannot establish WCAG conformance.

## Remediation result

| Surface | Source-level result | What changed |
|---|---|---|
| Inventory | Passed | `Dataset health` replaces ambiguous `Supply`; global start-block language was removed; consumer counts no longer conflate workers |
| Responsive inventory | Passed | One semantic table becomes labelled cards below 820 px and two-column cards at tablet width; all eight facts and the row action remain present |
| Detail shell | Passed | Dataset health and eligible supply are separate; tab route is represented in the hash and narrow tabs expose a scrollbar/hint |
| Supply scheduling | Passed at source level | Three mutually exclusive source specimens separate the no-change current state, accepted target increase and target decrease; the decrease stages `READY` to `STANDBY` without deletion and completes only after required consumers apply the revision |
| Supply/lifecycle | Passed at source level | All five canonical operation literals are shown with scope, attempt, fence, versions, timestamps and reconciled counters; a read-only `UNCERTAIN` cancellation-intent example retains its reservation and refuses a false `CANCELLED` claim |
| Consumers | Passed | Six complete bindings are reachable with working Previous/Next pagination; no worker/revision/decision detail is hidden at narrower widths |
| Evidence | Passed | A bounded proof-level/exact-target/flow-reference form precedes a canonical `DatasetProof/v1`; the versioned required-fact matrix is explicit, and `Attention` and serialized `Not requested` statuses were removed |
| Inspector | Passed | `Authoritative Dataset` and `This swarm applied` are separate groups with explicit revision, application and worker-coverage facts |
| Adverse states | Passed at source level | Queryable reconciling, forbidden, authorised-empty and incompatible states suppress fabricated current facts; stale/rate-limited states preserve only clearly historical evidence, mark current decisions unknown, and expose the exact refresh boundary |
| Keyboard/focus model | Passed at source level | Native controls, tabs with Arrow/Home/End, Escape-returning disclosure, route heading focus and live announcements are implemented |
| Post-change visual fidelity | Not claimed | Existing captures predate this content and are reference-only; deterministic recapture and visual inspection remain required |
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
- The responsive supply-definition disclosure is positioned below its trigger
  and constrained to the viewport in source; a current 320 CSS-pixel capture is
  still required.
- Mobile/tablet layouts retain exact tool observation UTC, compact consumer
  decision status, and Fitness evidence age.
- At 480 CSS pixels and below, the tool context and qualification chip switch
  to explicit short labels (`Dataset model`, `Unqualified`) so neither can
  overlap; their full accessible names remain in the DOM.

## Team-review visual evidence to refresh

The existing Firefox 140.12.0esr images are reference-only. A future
current-source run must render and inspect:

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
git diff --check
no duplicate mobile Dataset fixture
no 8 px or 9 px operational text
no deprecated operation literal in current wireframe source
no `Attention` or serialized `Not requested` proof status
```

Source-level semantic result: **passed**.

Planning visual-fidelity result for the current source: **not claimed; capture
refresh required**.

Production accessibility/readiness result: **not claimed by this planning
wireframe**.
