# Managed Datasets wireframe design QA

Scope note: this report covers visual fidelity and wireframe interactions only.
It is not a production-data, API, authorisation, security, accessibility or
operational-readiness pass. The normative semantic contract is
[Managed Datasets Operator UI Design Specification](../managed-datasets-operator-ui-design-spec.md).
Its section 22 amendments supersede any conflicting wireframe label or
relationship, and production code may not reuse the fictional values or
simulated Refresh/Logs/Inspect behavior in this folder.

## Comparison target

- Source visual truth: `/workspace/scratch/ec32db341c09/design-audit/01-dataset-inventory.png`
- Primary implementation evidence: `captures/datasets-desktop-1440.png`
- Matched QA implementation capture: `qa/implementation-datasets-1440x1040.png`
- Viewport: Firefox, 1440 x 1040, dark theme, Dataset inventory, no filters
- Full-view comparison: `qa/full-inventory-comparison.png` (source left,
  implementation right)
- Focused comparison: `qa/table-focus-comparison.png` (source table left,
  implementation table right)

Additional implementation evidence:

- `captures/spec-aligned/00-all-desktop.png` — all seven required desktop views;
- `captures/spec-aligned/00-all-mobile.png` — all seven required phone views,
  with each selected detail panel brought into the capture viewport;

- `captures/datasets-supply-tooltip-desktop-1440.png`
- `captures/dataset-detail-desktop-1440.png`
- `captures/dataset-fitness-desktop-1440.png`
- `captures/swarm-inspector-desktop-1440.png`
- `captures/datasets-tablet-1024.png`
- `captures/datasets-mobile-390.png`
- `captures/datasets-supply-tooltip-mobile-390.png`
- `captures/dataset-detail-mobile-390.png`
- `captures/swarm-inspector-mobile-390.png`

## Findings

No actionable P0, P1 or P2 visual differences remain.

The implementation intentionally changes the table's operational semantics
without changing PocketHive's visual language:

- Eligible supply is now current / target, with minimum and the truthful
  replenishment state in a hover, focus and tap explanation.
- Profile/environment, continuity and Fitness-check headings replace ambiguous
  labels.
- Important secondary text and Dataset names wrap instead of truncating.
- Current Fitness, prior-PASS continuity, supply coverage and worker application
  are separate facts.
- The Swarm Inspector dependency area is shorter and reports the three actual
  swarm start gates without implying that running traffic has stopped.

The extra table height is an intentional clarity trade-off. It remains within
the same card and viewport and avoids horizontal growth.

## Required fidelity surfaces

- Fonts and typography: the existing PocketHive Inter/system stack, weights,
  hierarchy and uppercase metadata treatment are retained. Main-table
  secondary copy remains 10 px and wraps; safety-critical Inspector detail was
  raised to 9 px.
- Spacing and layout rhythm: shell, navigation, summary cards, banner, filters,
  table grid, radii and spacing remain aligned to the source. The denser table
  grows vertically only where full text needs another line.
- Colors and visual tokens: existing dark/light semantic tokens and state pills
  are unchanged. Every state continues to have a text label in addition to
  colour.
- Image and asset fidelity: existing PocketHive logo and source icon assets are
  retained. No substitute imagery or placeholder asset was introduced.
- Copy and content: the current/effective state distinction and every count,
  threshold, revision and time label were reviewed across inventory, detail,
  Fitness, mobile cards and Inspector.

## Responsive and interaction verification

Firefox captures were inspected at 1440 px, 1024 px and 390 px. The desktop and
tablet tables remain within the content width; the phone layout uses semantic
cards with no horizontal scrolling or clipped safety-critical copy.

Primary interactions were exercised in Firefox 140.12 ESR:

- typing `refund` reduced the inventory to the correct single result;
- Clear restored all four results;
- clicking Eligible opened the exact supply explanation;
- Escape closed the pinned explanation while preserving focus;
- selecting the canonical Dataset opened its detail view; and
- selecting Fitness displayed the split latest-attempt/prior-PASS view.

The Firefox process log contained environment-only graphics/DBus warnings and
no page-script error. `node --check app.js` and `git diff --check` passed.

## Comparison history

1. P1: Eligible `12,480 / 5,000 target 15,000` was ambiguous and consumed width.
   Fix: compact current/target presentation plus exact accessible explanation.
   Post-fix evidence: supply-tooltip desktop and mobile captures.
2. P1: safety horizon, supply coverage, current Fitness and prior PASS were
   visually conflated. Fix: independent Continuity, Supply coverage, Latest
   Fitness attempt and Effective-for-existing-traffic facts. Post-fix evidence:
   Dataset overview and Fitness captures.
3. P2: full Dataset names and operational reasons were truncated. Fix: balanced
   column widths and wrapping. Post-fix evidence: focused table comparison.
4. P2: Inspector showed one blocker while three gates were unmet and repeated
   state pills. Fix: compact two-decision/four-fact dependency card with a
   three-gate blocker line. Post-fix evidence: Inspector desktop and mobile
   captures.
5. P2: an Escape key test initially closed the pinned popover but hover kept it
   visible. Fix: suppress hover/focus disclosure until pointer leave or focus
   change. The Firefox interaction check then confirmed Escape visibly closes
   it.

## Follow-up polish

No P3 item is required for this handoff. Production implementation should still
run automated contrast, screen-reader and zoom/reflow checks against the real
PocketHive component runtime.

final visual-fidelity result: passed

production semantic-readiness result: blocked pending the canonical read
contracts, all section 22 wireframe amendments, and `FUN-014`/`DSUI` evidence
