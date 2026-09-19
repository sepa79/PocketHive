# PocketHive Companion design QA

## Hive lifecycle, worker diagnostics, and Web UI actions — 2026-08-21

**Findings**

- No actionable P0, P1, or P2 difference remains.
- Hive preserves the selected lifecycle-accented swarm groups, exact worker
  disclosure, compact action hierarchy, full-width run history, and fixed
  service-health rail. Removal is visibly available but disabled for a running
  swarm; Start and Stop remain primary lifecycle actions.
- Buzz and Journal retain their approved compact one-line result treatment and
  add only exact Web UI affordances. They do not infer a Web UI endpoint from
  the MCP URL.
- Debug exposes separate `Logs`, `Inspect`, and `Version` worker tabs backed by
  their corresponding MCP tools. The cleanup plan is visibly plan-only and
  execution remains disabled because the extension is not an approval owner.
- The health disclosure intentionally keeps the established upward chevron
  when closed and downward chevron when open because the bottom drawer opens
  upward. Workers, run history, and other content disclosures use right when
  closed and down when open.

**Evidence**

- Source visual truth:
  - Hive: `/home/tim/.codex/generated_images/019ff6b2-bbdb-75a0-8af0-bc8144c37a02/exec-b6b68b2c-9981-49b6-a0dc-b52bf393ad68.png`
    (`857 × 1836` pixels).
  - Debug: `/home/tim/.codex/generated_images/019ff6b2-bbdb-75a0-8af0-bc8144c37a02/exec-1b9e0e32-6774-4cee-af95-63895633da72.png`
    (`856 × 1836` pixels).
- Browser-rendered implementation:
  - `reports/playwright-ui/11-selected-hive.png` (`428 × 917` pixels),
    collapsed worker/history/service state.
  - `reports/playwright-ui/11a-selected-hive-workers-health.png`
    (`428 × 917` pixels), exact worker and service-health state expanded.
  - `reports/playwright-ui/16-selected-debug.png` (`428 × 917` pixels),
    exact worker Logs state.
  - `reports/playwright-ui/16-selected-debug-swarm-tools.png`
    (`428 × 917` pixels), scrolled swarm-tool state.
  - `reports/playwright-ui/16a-selected-debug-cleanup-plan.png`
    (`428 × 917` pixels), cleanup evidence and locked execution state.
- Full-view side-by-side comparisons:
  - `reports/playwright-ui/design-qa-lifecycle-hive-expanded-comparison.png`
    (`856 × 917` pixels; source left, implementation right).
  - `reports/playwright-ui/design-qa-runtime-debug-comparison.png`
    (`856 × 917` pixels; source left, Logs implementation right).
- Focused state comparison:
  - `reports/playwright-ui/design-qa-runtime-debug-cleanup-comparison.png`
    (`856 × 917` pixels; source left, cleanup implementation right).
- Viewport and state: `428 × 917` CSS pixels, device scale factor 1, dark
  VS Code theme, authenticated `Local MCP`, exact running and ready swarms,
  live service-health evidence, and one exact Debug runtime target.
- Density normalization: each source was resized to `428 × 917` before being
  joined to the implementation. The implementation captures are native
  `428 × 917` browser pixels.

**Required fidelity surfaces**

- Typography: VS Code host/editor tokens preserve the selected compact
  hierarchy, readable worker metadata, monospace runtime evidence, and
  one-line action labels without introducing a duplicate product header.
- Spacing and layout rhythm: cards remain single-column at Side Bar width;
  lifecycle groups have a clear edge and gap; secondary actions occupy three
  equal horizontal grid sections;
  run history owns the full inner width; the service drawer meets the fixed
  rail without trailing whitespace.
- Colors and tokens: restrained navy surfaces, cyan actions, yellow active
  PocketHive tab, lifecycle green/blue, muted disabled removal, and semantic
  healthy/unavailable states match the established extension theme.
- Asset quality: PocketHive identity uses the packaged repository-generated
  hexagon asset. Tabs, actions, services, and diagnostics use the pinned local
  VS Code Codicon font; no emoji, placeholder, CSS-art, or approximate inline
  SVG asset is present.
- Copy and content: exact swarm IDs, worker instances/images, target IDs,
  owner-returned health endpoints, plan hash, risk, and cleanup candidates are
  shown without invented labels or inferred owner state.
- Responsiveness and accessibility: the browser gate exercised 140, 240, 280,
  320, 428, and 480 CSS-pixel widths and reported no accessibility, overflow,
  clipping, target-size, console, page, or interaction finding.

**Interaction and operational verification**

- Hive acceptance exercised Start, Stop, disabled running-swarm removal,
  confirmed ready-swarm removal command, Debug navigation, exact Web UI
  navigation, worker expansion, and single-owner run-history expansion.
- Debug acceptance verified the exact `Logs | Inspect | Version` tabs, Docker
  stdout/stderr label, selected runtime evidence, six swarm tools, cleanup
  candidate projection, and disabled `Execute cleanup` control.
- Live acceptance connected through the public local MCP ingress, observed 54
  tools including all three worker diagnostic tools, rotated the OAuth refresh
  token, rejected retired-token replay, captured 33 UI/auth states, and
  reported zero findings.
- A disposable swarm passed create, 3/3 readiness, start, exact worker Logs,
  owner-provided Version, the bounded Orchestrator Docker Inspect projection,
  stop, and remove through MCP; the pre-existing swarm was not operated.
- Unit tests passed 141/141. Mutation testing killed 2,373 mutants by
  assertion and nine by timeout, with zero survivors, uncovered mutants, or
  errors: 100.00%.
- MCP PIT killed 613/613 mutants with 1,432/1,432 covered lines: 100.00%.

**Comparison history**

- Pass 1 found the migrated Hive surface retained a technical Details action,
  separated Remove from related actions, and lacked exact Web UI affordances.
  Debug also treated broader diagnostic responses as substitutes for explicit
  container Logs, Inspect, and Version contracts.
- Pass 2 introduced exact worker/lifecycle actions, authoritative Web UI
  routing, separate diagnostic tools/evidence, locked cleanup execution, and
  service icons with a neutral globe for unexpected service IDs.
- Pass 3 added like-for-like expanded Hive and cleanup captures. The combined
  visual review found no remaining P0/P1/P2 issue. The implementation keeps the
  user-approved health-arrow exception rather than applying ordinary content
  disclosure direction to the upward-opening footer.

**Follow-up polish**

- None required for this change.

## Hive expanded-worker target correction — 2026-08-21

**Evidence**

- Source visual truth: `/home/tim/.codex/generated_images/019ff6b2-bbdb-75a0-8af0-bc8144c37a02/exec-b6b68b2c-9981-49b6-a0dc-b52bf393ad68.png`.
- Browser-rendered implementation: `reports/playwright-ui/11a-selected-hive-workers-health.png`.
- Normalized full-view comparison: `reports/playwright-ui/hive-target-comparison-final.png`.
- Source pixels: 857 × 1836. Implementation pixels and CSS viewport: 428 × 917 at device scale factor 1.
  The source was normalized to 428 × 917 and joined beside the implementation in one 856 × 917 comparison.
- State: authenticated local environment; Hive selected; running `checkout-load` with Workers expanded;
  stopped `nightly-smoke`; environment-health drawer expanded; running Remove disabled.

**Required fidelity surfaces**

- Fonts and typography: the VS Code host font remains the authoritative family. Heading, status, metadata,
  worker-role, action, endpoint, and service-status weights and line heights preserve the target hierarchy without
  clipping or unwanted wrapping.
- Spacing and layout rhythm: the implementation matches the target's mobile-width single column, compact top
  navigation, full-width worker disclosure, inline worker resources, distinct swarm action row, full-width Run
  history, separate swarm lifecycle edges, and slim fixed service drawer. Expanded workers do not overlap or
  displace swarm actions.
- Colors and tokens: PocketHive yellow remains exclusive to the active Hive tab; cyan actions, green running/healthy,
  blue ready, red unavailable, dark navy surfaces, and muted border tokens align with the source.
- Image and asset quality: swarm and environment identity use the canonical repository-generated PocketHive mark.
  Worker, diagnostic, lifecycle, and health icons use the packaged official Codicon font; no substitute SVG, CSS art,
  emoji, or placeholder assets were introduced.
- Copy and content: `Workers`, `Inspect`, `Logs`, `Debug`, `Open in Web UI`, `Remove`, and `Run history` match the
  target's task-oriented vocabulary. Dynamic instance names intentionally reflect MCP owner data rather than the
  static names in the design reference.
- Responsiveness and accessibility: the browser gate covers 140, 240, 280, 320, 428, and 480 CSS-pixel widths.
  Worker actions have explicit accessible names, closed disclosures point right, open disclosures point down, and
  running Remove remains disabled with explanatory title text.

**Focused region comparison**

- A separate crop was unnecessary: at 856 × 917 the joined full-view evidence keeps the complete tab strip, both
  swarm groups, worker rows/actions, history rows, service icons/statuses, and footer identity readable.
- Playwright separately measured the expanded worker region: disclosure and resource list widths match; resources
  follow the summary; swarm actions follow the resources; Debug, Web UI, and Remove fill three equal horizontal
  grid sections on one line.

**Interaction and operational evidence**

- Tested Start, Stop, Debug, Web UI, disabled/enabled Remove, Workers expand/collapse, exact per-worker Inspect and
  Logs messages, Run history, environment-health expansion, and service/account footer behavior.
- The browser run captured 33 states with zero findings and no console/page errors.

**Comparison history**

- Initial implementation placed Workers in the flex action row, causing the expanded worker list to float into a
  narrow overlapping column. It also used a tall service drawer and allowed swarm actions to wrap.
- The first corrected comparison found two remaining P2 density differences: excess top headspace and service-row
  height hid the second swarm's action/history hierarchy behind the fixed drawer. The fix tightened the header,
  search, worker rows, and health rows while retaining usable controls.
- The next comparison exposed missing worker-action separation and a non-target runtime icon. The fix applied the
  Codicon package glyph and explicit action dividers.
- Final post-fix evidence has no actionable P0/P1/P2 difference. Remaining text differences are dynamic owner data;
  the implementation intentionally uses exact MCP instance identifiers.

**Implementation Checklist**

- [x] Full-width Workers disclosure inside each swarm group.
- [x] Exact worker rows with immediate Inspect and Logs actions.
- [x] Separate three-section horizontal Debug, Web UI, and Remove action grid.
- [x] Running Remove disabled; stopped ready Remove enabled.
- [x] Full-width Run history remains last in each swarm group.
- [x] Slim fixed health drawer retains named service icons, endpoints, and status.
- [x] 428 × 917 visual comparison plus responsive geometry and interaction gates.

**Follow-up Polish**

- None required for this target.

final result: passed

## Scenario compact section tabs — 2026-08-21

**Findings**

- No actionable P0, P1, or P2 difference remains.
- Scenario `Overview | Files | Inputs` now uses the same full-width,
  three-column segmented treatment as Debug `Logs | Inspect | Versions`.
- The implementation introduces one neutral compact-tab style authority shared
  by both surfaces; Scenario is not coupled to Debug-specific class names.
- Tab labels, icons, selected section, commands, and Overview, Files, and Inputs
  content remain unchanged.

**Evidence**

- Source visual truth: `reports/playwright-ui/16-selected-debug.png`
  (`428 × 917` pixels), using the established Debug diagnostic tabs.
- Browser-rendered implementation:
  `reports/playwright-ui/15-selected-scenarios.png` (`428 × 917` pixels),
  with Files selected.
- Additional content-state captures:
  `reports/playwright-ui/15a-selected-scenario-overview.png` and
  `reports/playwright-ui/15-selected-scenarios-inputs.png`, both
  `428 × 917` pixels.
- Full-view side-by-side comparison:
  `reports/playwright-ui/design-qa-scenario-tabs-comparison.png`
  (`856 × 917` pixels; Debug reference left, Scenario implementation
  right).
- Viewport and state: `428 × 917` CSS pixels, device scale factor 1, dark
  VS Code theme, authenticated local environment, one exact deployed Scenario
  Bundle expanded.
- Density normalization was unnecessary because both captures use the same CSS
  viewport, pixel dimensions, theme, and device scale factor.
- Focused comparison was unnecessary because both three-control tab strips and
  their selected indicators remain readable in the full-view comparison.

**Required fidelity surfaces**

- Fonts and typography: both strips use identical VS Code host typography,
  label weight, line height, truncation behavior, and Codicon sizing.
- Spacing and layout rhythm: both use equal-width columns, a 38 px minimum
  control height, shared dividers, square internal joins, and the same compact
  top-corner radius.
- Colors and visual tokens: muted inactive labels, cyan active text and
  underline, raised active surface, and restrained borders share the same
  extension tokens.
- Image quality and asset fidelity: no raster asset is required. Existing
  official pinned Codicons are retained without substitution.
- Copy and content: `Overview`, `Files`, and `Inputs` and all section content
  are unchanged; the unit contract asserts the exact label order and retained
  selected Files state.
- Responsiveness and accessibility: the live browser gate covered 28 states,
  including narrow widths, and reported no Axe, overflow, clipping, console,
  page, or interaction findings.

**Interaction and browser verification**

- Scenario section buttons still emit the existing exact
  `selectScenarioSection` commands and preserve the expanded Scenario row.
- Overview, Files, and Inputs each rendered with their existing content.
- The established Debug controls retained their tab semantics and exact Logs,
  Inspect, and Versions actions after adopting the shared style authority.

**Comparison history**

- Pass 1 identified the detached Scenario chips as the sole P2 visual mismatch
  against the selected Debug treatment.
- Pass 2 replaced the chips with the shared segmented strip. The complete live
  browser gate then captured all three Scenario states and reported zero
  findings; no actionable mismatch remains.

**Follow-up polish**

- No P3 item is required for this change.

final result: passed

## Environment health footer and account overlay — 2026-08-21

**Findings**

- No actionable P0, P1, or P2 difference remains.
- The implementation preserves the selected option-1 hierarchy: a fixed
  environment rail, PocketHive hexagon environment mark, inline health state,
  upward service disclosure, adjacent account trigger, and account card that
  overlays the disclosure without reserving layout space.
- The production disclosure is intentionally denser than the generated visual.
  It shares a 428 px VS Code Side Bar with the active workspace and includes the
  contract's seventh service, Network Proxy Manager. Compact divider-led rows
  keep every service and exact public-ingress endpoint scannable without
  creating the rejected whitespace below the final service.
- The live WireMock row is unavailable because that is the observed local MCP
  result. The UI does not replace owner evidence with the generated visual's
  all-healthy example.

**Evidence**

- Source visual truth:
  `/home/tim/.codex/generated_images/019ff6b2-bbdb-75a0-8af0-bc8144c37a02/exec-5eab3f59-8395-4a4a-8832-264d0dd44b96.png`
  (`943 × 1667` pixels; selected option 1).
- Browser-rendered implementation:
  `reports/playwright-ui/20-environment-health-account-overlay.png`
  (`428 × 917` pixels).
- Normalized source:
  `reports/playwright-ui/design-qa-environment-footer-normalized.png`
  (`428 × 917` pixels).
- Full-view side-by-side comparison:
  `reports/playwright-ui/design-qa-environment-footer-comparison.png`
  (`856 × 917` pixels; source left, implementation right).
- Viewport and state: `428 × 917` CSS pixels, device scale factor 1, dark
  theme, authenticated `Local MCP`, health disclosure expanded, account menu
  expanded, and live MCP service evidence present.
- Density normalization: the source was scaled proportionally to 428 px wide
  and centred on a `428 × 917` neutral canvas. The generated concept and the
  production workspace intentionally contain different main content, so the
  comparison judges the shared navigation, disclosure, footer, and account
  regions rather than inventing pixel precision for the workspace body.
- Focused evidence was not required: at 856 px combined width, the full-view
  comparison keeps the logo, service labels/endpoints, statuses, footer, and
  account action readable.

**Required fidelity surfaces**

- Fonts and typography: the production uses VS Code host and editor-monospace
  tokens. Environment identity, service name, endpoint, semantic state, and
  account hierarchy remain distinct at a denser but readable scale; bounded
  endpoints truncate rather than widening the Side Bar.
- Spacing and layout rhythm: the drawer is full width with square top corners,
  meets the fixed rail directly, and ends exactly at the last service row. The
  account card is absolutely positioned over the service list and creates no
  trailing layout gap.
- Colors and visual tokens: near-black/navy surfaces, restrained dividers,
  cyan focus/action treatment, yellow PocketHive/Hive identity, green healthy
  state, and red unavailable state use the established extension tokens.
- Image quality and asset fidelity: the environment mark is the packaged
  repository-generated `resources/logo-mark.svg` PocketHive hexagon. Other
  controls use the pinned official VS Code Codicon font; no emoji, CSS art,
  inline SVG approximation, or placeholder asset is present.
- Copy and content: the footer names the selected environment, reports a
  bounded service summary, lists only MCP-owned service facts, identifies the
  current principal, and provides one direct `Sign out` action.
- Responsiveness and accessibility: the closed rail remains usable at the
  280 px stress width. The workspace retains one screen-reader-only `h1`
  without restoring visual headspace. The live gate found no Axe, overflow,
  clipping, console, page, or control-target issue across all 28 states.

**Interaction and browser verification**

- Expanding health exposes the exact seven MCP service rows and updates
  `aria-expanded`; the drawer's square corners and zero panel/rail and
  row/panel gaps are measured in-browser.
- Opening Account preserves drawer geometry, presents the authenticated
  principal and environment, and emits the existing exact `signOut` message.
- The public-ingress run authenticated through `http://localhost:8088/mcp`,
  read 54 tools plus `pockethive://environment/health`, rotated the session,
  rejected refresh-token replay, captured 28 states, and reported zero
  findings.

**Comparison history**

- Pass 1 found that removing the old visible identity heading also removed the
  document's level-one heading in every workspace state. A screen-reader-only
  environment `h1` was added; it creates no visual headspace.
- Pass 2 reran the complete live browser gate. All 28 captures, including the
  expanded health/account state and 280 px stress state, passed with zero
  findings. The normalized side-by-side comparison found no remaining
  actionable P0, P1, or P2 difference.

**Follow-up polish**

- No P3 item is required for this release.

final result: passed

## Debug target-first diagnostic workspace — 2026-08-20

**Findings**

- No actionable P0, P1, or P2 difference remains.
- The implementation preserves the approved task hierarchy: compact
  `Worker | Swarm` context navigation, one exact runtime target, retained exact
  worker selection, `Logs | Inspect | Versions` modes with adjacent bounded
  evidence, a two-column swarm-tool matrix at normal Side Bar width, and a
  separate plan-only Maintenance surface.
- The implementation intentionally uses the shared VS Code shell's larger host
  type and minimum control targets. The generated source fits every section in
  one 917 px viewport by using substantially smaller controls; the production
  view keeps accessible targets and exposes the lower tools through ordinary
  vertical scrolling plus the explicit Swarm context jump. This is an expected
  host/accessibility constraint, not unresolved visual drift.
- The implementation keeps the established PocketHive yellow selected-tab
  token instead of the generated image's cyan Debug tab. Cyan remains the local
  diagnostic/action accent. This preserves the approved cross-page theme.

**Evidence**

- Source visual truth:
  `/home/tim/.codex/generated_images/019ff6b2-bbdb-75a0-8af0-bc8144c37a02/exec-e0033e17-14ec-4616-b3a1-cddc74c8dbba.png`
  (`857 × 1836` pixels).
- Browser-rendered implementation:
  `reports/playwright-ui/16-selected-debug.png` (`428 × 917` pixels).
- Normalized source:
  `reports/playwright-ui/design-qa-debug-selected-normalized.png`
  (`428 × 917` pixels).
- Full-view side-by-side comparison:
  `reports/playwright-ui/design-qa-debug-comparison.png`
  (`856 × 917` pixels; source left, implementation right).
- Focused lower-state evidence:
  `reports/playwright-ui/16-selected-debug-swarm-tools.png`
  (`428 × 917` pixels), captured after activating the Swarm context.
- Viewport and state: `428 × 917` CSS pixels, device scale factor 1, dark
  theme, authenticated local environment, exact swarm `checkout-load`, exact
  runtime `request-builder-7f8c9`, Logs active, bounded evidence present.
- Density normalization: the generated `857 × 1836` source was normalized to
  the exact `428 × 917` implementation size. The focused lower-state capture
  was required because accessible production controls place Swarm tools and
  Maintenance below the initial fold.

**Required fidelity surfaces**

- Fonts and typography: the production view uses the VS Code host font and
  editor monospace font. Heading, label, selected-tab, evidence, and metadata
  hierarchy match the source; long exact identifiers truncate only in bounded
  label spans and keep their full title.
- Spacing and layout rhythm: section order, one coherent runtime surface,
  adjacent evidence, two-column 428 px tool matrix, compact Maintenance row,
  shared shell, and fixed health rail match the selected direction. At narrower
  widths the tool matrix becomes one column and diagnostic labels become
  icon-led without horizontal overflow.
- Colors and visual tokens: raised navy surfaces, restrained borders, cyan
  diagnostic actions, amber plan-only treatment, green connection state, and
  the established yellow active workspace tab all use existing PocketHive/VS
  Code tokens.
- Image quality and asset fidelity: the design requires no raster content.
  Every visible interface icon uses the pinned official VS Code Codicon asset;
  no emoji, CSS art, inline SVG, placeholder icon, or generated substitute was
  introduced.
- Copy and content: supported action names, exact target labels, bounded MCP
  evidence, and `Plan only` wording match the current MCP contract. The local
  environment and runtime identifiers are live-test data rather than invented
  product claims.

**Interaction and browser verification**

- Worker and Swarm context controls activate and navigate to their owned
  section; the Swarm state has a dedicated browser capture.
- Exact swarm and exact worker controls remain searchable comboboxes and reject
  values outside the bounded current result.
- Logs emits the existing exact `runDebug` message with `tailLines: 200`;
  Inspect, Versions, Workers, swarm reads, and Cleanup plan retain the one
  canonical mapping.
- The live public-ingress acceptance run authenticated through
  `http://localhost:8088/mcp`, exercised 26 browser states at 140–480 CSS px,
  checked Axe, page errors, console errors, clipping, overflow, and target
  sizes, and reported zero findings.

**Comparison history**

- Pass 1 found invalid `aria-controls` values before evidence existed and
  clipped labels/chevrons at 140–320 px. The empty bounded-evidence tabpanel was
  made explicit, labels became independently truncatable, and sub-360 px swarm
  tools became one column. The next live run reported zero findings.
- Pass 2 found the Swarm context used nearest scrolling, which did not move when
  the tool surface was partly visible. The context now requests start-aligned
  navigation, the acceptance gate proves a non-zero scroll and active state,
  and the final live run again reported zero findings.

**Follow-up polish**

- No P3 item is required for this release.

final result: passed

## Hive grouped lifecycle surface — 2026-08-20

**Latest findings**

- No actionable P0, P1, or P2 difference remains.
- The implementation preserves the approved hierarchy: one primary Create
  action, a quiet batch toolbar, status beside swarm identity, one bounded
  lifecycle-accented group per swarm, compact supporting actions, and a
  full-width collapsed Run history disclosure owned by that swarm.
- The implementation is intentionally denser than the generated source image.
  It uses VS Code host font and control tokens and retains verified usability at
  140–480 CSS-pixel Side Bar widths; increasing the generated mock's text and
  card scale would reduce useful operator density and regress the existing
  200%-zoom equivalent gate.

**Latest evidence**

- Source visual truth:
  `/home/tim/.codex/generated_images/019ff6b2-bbdb-75a0-8af0-bc8144c37a02/exec-9bc7030e-09cf-4a9a-92a2-bba43b0c2210.png`
  (`857 × 1836` pixels).
- Browser-rendered implementation:
  `reports/playwright-ui/11-selected-hive.png` (`428 × 917` pixels).
- Normalized source:
  `reports/playwright-ui/design-qa-hive-selected-normalized.png`
  (`428 × 917` pixels).
- Full-view side-by-side comparison:
  `reports/playwright-ui/design-qa-hive-comparison.png`
  (`856 × 917` pixels; source left, implementation right).
- Viewport and state: `428 × 917` CSS pixels, device scale factor 1, dark
  theme, authenticated local environment, one RUNNING and one READY swarm,
  both histories collapsed.
- Density normalization: the generated `857 × 1836` source was normalized to
  the exact `428 × 917` implementation size before comparison. The full-view
  comparison keeps the complete readable Hive surface, so a focused crop was
  not needed.

**Latest fidelity pass**

- Fonts and typography: VS Code host typography is smaller than the generated
  approximation by design, but hierarchy, weight, wrapping, status adjacency,
  and readable labels match the selected direction.
- Spacing and layout: each swarm has an independent tinted surface, 3 px
  lifecycle edge, 10 px inter-group gap, primary and secondary action rows, and
  a separate full-width history row. Search, batch actions, and the fixed health
  rail retain the selected vertical order.
- Colors and tokens: PocketHive yellow marks the active Hive tab; cyan controls,
  green RUNNING/connected state, blue READY state, navy surfaces, and restrained
  borders all use existing theme tokens.
- Image and icon fidelity: swarm identity uses the repository-generated
  PocketHive mark; tabs and actions use the pinned official Codicon asset. No
  placeholder, emoji, CSS-art, or new raster substitute was introduced.
- Copy and content: exact swarm names, template IDs, bee counts, lifecycle
  states, supported actions, and MCP/session health wording match the approved
  visual and current MCP contract.

**Latest comparison history**

- The first selected refinement left swarm boundaries too weak. The approved
  follow-up added a lifecycle-coloured edge, subtle group surface, explicit
  spacing, and a full-width history disclosure.
- The implementation reproduced those refinements. The browser acceptance gate
  initially exposed that the history-width assertion ignored the group's 4 px
  combined border width; the assertion was corrected to measure the actual
  inner full-width contract. No UI fix was hidden by that correction.
- Post-fix evidence is the comparison and implementation capture above. The
  browser gate reports 33 screenshots, zero console/page/accessibility findings,
  and successful live interaction through the public local MCP ingress.

**Findings**

- No actionable P0, P1, or P2 difference remains.
- The fixed rail intentionally says `MCP connected` and `Session active` instead
  of the mockups' `Core 4/4` and `Mocks 2/2`. The extension has authoritative
  MCP/session evidence but no MCP contract for per-service, Grafana, or mock
  health. Presenting those counts would create a second, false health authority.
- Hive intentionally retains exact batch lifecycle, Details, and per-swarm run
  history controls that exist in the migrated product. The approved visual was
  used for hierarchy and treatment, not to delete supported functionality.
- Buzz and Journal show two deterministic acceptance events rather than the
  mockups' larger visual-only lists. Their row geometry, state treatment, and
  one-line information hierarchy are equivalent.
- Scenario Files now derives real parent/child branches from Scenario Manager's
  bundle-relative paths. The four redundant file/detail shortcuts shown in the
  defect capture are intentionally removed because Files is the single
  file-navigation surface. Scenario Overview intentionally replaces the defect
  capture's two-column/truncated cards with full-width, untruncated Description,
  Controller, and Bees rows.

**Evidence**

- Source visual truth:
  - Environments: `/home/tim/.codex/generated_images/019ff6b2-bbdb-75a0-8af0-bc8144c37a02/exec-c43facae-e9ab-4eb2-98b6-d1129770721f.png`
  - Hive: `/home/tim/.codex/generated_images/019ff6b2-bbdb-75a0-8af0-bc8144c37a02/exec-9bc7030e-09cf-4a9a-92a2-bba43b0c2210.png`
  - Buzz: `/home/tim/.codex/generated_images/019ff6b2-bbdb-75a0-8af0-bc8144c37a02/exec-450efd68-8c8a-41f5-bb57-4b862fa2d3f9.png`
  - Journal: `/home/tim/.codex/generated_images/019ff6b2-bbdb-75a0-8af0-bc8144c37a02/exec-e2afaf64-4b47-4f8e-939f-dbaa643752e2.png`
  - Scenarios: `/home/tim/.codex/generated_images/019ff6b2-bbdb-75a0-8af0-bc8144c37a02/exec-9c2f94d5-7ed0-46d7-a37c-05926a0cad54.png`
  - Debug: `/home/tim/.codex/generated_images/019ff6b2-bbdb-75a0-8af0-bc8144c37a02/exec-e1980925-821c-4144-b079-a613afe861c0.png`
  - Scenario Files defect capture: `/home/tim/Pictures/Selection_027.png`
  - Scenario Overview defect capture: `/home/tim/Pictures/Selection_028.png`
- Browser-rendered implementation:
  - `reports/playwright-ui/10-selected-environments.png`
  - `reports/playwright-ui/11-selected-hive.png`
  - `reports/playwright-ui/14-selected-buzz.png`
  - `reports/playwright-ui/13-selected-journal.png`
  - `reports/playwright-ui/15-selected-scenarios.png`
  - `reports/playwright-ui/15a-selected-scenario-overview.png`
  - `reports/playwright-ui/15-selected-scenarios-inputs.png`
  - `reports/playwright-ui/16-selected-debug.png`
- Normalized side-by-side comparisons:
  - `reports/playwright-ui/design-qa-environments-comparison.png`
  - `reports/playwright-ui/design-qa-hive-comparison.png`
  - `reports/playwright-ui/design-qa-buzz-comparison.png`
  - `reports/playwright-ui/design-qa-journal-comparison.png`
  - `reports/playwright-ui/design-qa-scenarios-comparison.png`
  - `reports/playwright-ui/design-qa-scenario-files-comparison.png`
  - `reports/playwright-ui/design-qa-scenario-overview-comparison.png`
  - `reports/playwright-ui/design-qa-debug-comparison.png`
- Viewport: 428 × 917 CSS px, dark theme, authenticated local environment,
  device scale factor 1. The implementation pixels are 428 × 917. Source images
  are approximately 856/857 × 1835/1836 and were normalized to 428 × 917 before
  being joined to the implementation; each comparison is 856 × 917.
- States: connected environment; Hive with running and ready swarms; Buzz and
  Journal event streams; Scenarios with the Files drill-down expanded plus a
  separate full-width Overview and Inputs capture; Debug with `checkout-load`
  selected.

**Required fidelity surfaces**

- Fonts and typography: VS Code host font tokens preserve the compact hierarchy,
  restrained weights, readable line height, one-line tabs, and one-line event
  summaries. Long owner values use deliberate truncation or wrapping with exact
  detail access.
- Spacing and layout: the implementation preserves the source's single mobile
  column, tight header, five-tab strip, lifecycle-accented swarm groups, grouped
  actions, full-width history disclosures, and fixed bottom rail. Additional
  vertical space belongs only to retained product controls or expanded Scenario
  content.
- Colors and tokens: the navy/near-black surfaces, cyan actions, green live
  state, red error state, muted borders, and canonical PocketHive yellow active
  tab align with the approved direction. No decorative gradient was introduced.
- Image and asset quality: environment, swarm, and scenario identity use the
  repository-generated full-colour PocketHive mark. Navigation and actions use
  the packaged official VS Code Codicon font. No emoji, CSS art, inline SVG, or
  placeholder image substitutes are present.
- Copy and content: labels remain direct and task-oriented. Health wording is
  constrained to observable MCP truth. Exact-target and publication copy retains
  PocketHive's no-inference and no-fallback semantics.
- Icons: all five tabs and primary actions use one coherent local icon family,
  with accessible text names retained. Icon-only disclosures have explicit
  accessible names and titles.
- Responsiveness and accessibility: Playwright exercised 140, 240, 280, 320,
  428, and 480 CSS-pixel widths, including the 140-pixel 200%-zoom equivalent.
  Tabs remain usable, the selected tab stays visible, and no page overflow,
  clipping, undersized target, or Axe violation was recorded.

**Focused region comparison**

- Header, tabs, section headings, action hierarchy, content rows, and the fixed
  rail remain readable in each full-height 856 × 917 side-by-side comparison,
  so separate crops were not required for those regions.
- Dense Scenario details were additionally inspected in focused 808-pixel-wide
  source/implementation comparisons. The Files comparison normalizes the
  504 × 809 source to a 404-pixel column and compares it with a 404 × 574 crop
  from the 428 × 917 implementation. The Overview comparison normalizes the
  465 × 296 source to a 404-pixel column and compares it with a 404 × 326 crop.
  These prove complete nested ancestry, exact file preview targets, equal-width
  Overview rows, and Variables/Auth/SUT/endpoint presentation at target width.
- Debug was compared with an exact swarm selected so enabled/disabled action
  hierarchy could be judged rather than relying on an empty shell.

**Interaction and operational evidence**

- Primary interactions tested: add/connect/save/open environment, browser OAuth,
  account/sign-out, keyboard and pointer tab navigation, Start/Stop/Remove,
  collapsible single-swarm run history, exact-run Journal navigation, collapsed
  advanced filters, exact folder filtering, searchable exact-swarm selection,
  expandable Scenario Files hierarchy, full-width Overview, Inputs drill-down,
  and grouped Debug actions.
- The browser gate used the public local MCP ingress, observed 54 tools, rotated
  the base refresh token, rejected retired-token replay, captured 33 product and
  auth states, and reported zero finding.
- Browser page errors and console errors were collected; none were reported.
- Unit/cutover gate: 141/141 tests passed. Mutation gate: 2,373 killed by
  assertion and nine killed by timeout, zero survivors/uncovered/errors,
  100.00% score. Dependency audit: zero vulnerabilities. VSIX allow-list:
  41 extension files before packaging.

**Comparison history**

- Earlier review found that the first implementation had text-only tabs and
  actions, permanently visible filter fields, no fixed health rail, and a native
  datalist that did not provide the intended integrated choice surface.
- The implementation added the official local Codicon family, collapsed advanced
  filters, an accessible searchable listbox, icon-led cards/actions, and a fixed
  expandable MCP/session rail.
- The first browser pass then found an absent explicit combobox name, an obsolete
  text-based More selector, and 200%-zoom compression. Those were fixed with an
  explicit accessible name, semantic icon-summary targeting, a scrollable
  ultra-narrow tab strip, and compact diagnostic headings. Post-fix browser
  evidence reported zero finding.
- The first full comparison showed that Scenarios and Debug were captured before
  their implemented drill-down/target states. The acceptance model was corrected
  to expand the exact mixed-file Scenario tree, capture Inputs separately, and
  select the Debug swarm. The final comparison images listed above are post-fix.
- A later user capture exposed a flat Scenario file list that visually repeated
  full paths and a two-column Overview that truncated Description. The fix
  builds one hierarchy from canonical bundle-relative paths, nests every child
  under its exact directory, removes the four duplicate shortcuts, and forces
  Description, Controller, and Bees into full-width rows. The two focused
  post-fix comparisons listed above have no remaining P0/P1/P2 finding.

**Implementation Checklist**

- [x] One professional mobile-width visual system across Environments and all five tabs.
- [x] Distinct lifecycle-accented swarm groups with full-width history disclosures.
- [x] Fixed slim truthful MCP/session rail on every workspace tab.
- [x] Official local icons for tabs, actions, status, filters, files, and diagnostics.
- [x] One-line Buzz/Journal rows with collapsed advanced fields.
- [x] Searchable exact-swarm combobox/listbox with explicit target validation.
- [x] Scenario folder filter, nested Files hierarchy, full-width Overview, and
      Inputs drill-down evidence.
- [x] Responsive, keyboard, accessibility, live-ingress, mutation, audit, and package gates.

**Follow-up Polish**

- None required for this handoff.

## Repository Scenario Bundle cards — 2026-08-23

**Selected source and implementation**

- Approved Option 1 source:
  `reports/design-targets/repository-scenarios-option-1.png`
  (`866 × 1817` pixels).
- Browser implementation:
  `reports/playwright-ui/15b-repository-scenarios.png`
  (`428 × 917` pixels).
- Normalised side-by-side comparison:
  `reports/playwright-ui/repository-scenarios-comparison.png`
  (`856 × 917` pixels; source left, implementation right).
- Explicit conflict state:
  `reports/playwright-ui/15c-repository-deployment-conflict.png`
  (`428 × 917` pixels).

The implementation matches the selected single-column hierarchy: source
switch, compact search and collapsed workspace filter, one independently
expandable card per committed bundle, a three-section `Edit | Validate |
Deploy` action row, compact `Overview | Files | Inputs` tabs, and a nested
mixed-file tree. The implementation retains additional real `sut` and
`variables.yaml` files because the Git tree is authoritative; the source image
was a visual fixture, not a content contract.

Unvalidated cards say `Repository`. They show `Valid`, exact scenario ID, and
exact scenario name only after the Scenario Manager-owned receipt supplies
those facts. An existing deployed ID opens a focused replace-or-rename dialog.
The dialog uses the approved `-01` suggestion and explains the required
edit/commit/refresh/revalidate journey. It overlays the card surface and no
longer reveals a duplicate raw JSON result below the card.

Playwright exercised repository search, advanced workspace filtering, exact
`scenario.yaml` edit, nested-file edit, validation, create deployment,
replace, rename, and cancel messaging. It also checked the complete companion
at 140, 240, 280, 320, 428, and 480 CSS pixels. The final run captured 35
product/auth states and reported zero page, console, Axe, overflow, clipping,
outside-viewport, undersized-target, or raw-owner-data findings.

**Implementation checklist**

- [x] Self-contained cards with no bottom-of-page action dependency.
- [x] Exact committed HEAD file hierarchy and editor actions.
- [x] Owner-returned identity only; no client inference.
- [x] Explicit create or replace/rename conflict path.
- [x] `-01` rename suggestion followed by edit and revalidation.
- [x] No duplicate raw validation result in Repository.
- [x] Responsive, keyboard, accessibility, live-ingress, and mutation gates.

## Writable Repository editing and focused event actions — 2026-08-23

- Repository Edit opens only the exact trusted worktree `file:` URI and checks
  host write access first. An unwritable file returns the explicit
  `REPOSITORY_SCENARIO_FILE_NOT_WRITABLE` state; the extension does not change
  permissions or substitute a Git-object preview.
- Buzz and Journal event disclosures retain technical details and applicable
  Web UI navigation. Their redundant Debug buttons are removed; diagnostics
  remain in the dedicated Debug workspace and Hive swarm actions.
- Unit tests cover writable and denied editor boundaries plus the absence of
  event-row Debug controls in both tabs.
- The post-fix public-ingress Playwright run captured 35 product/auth states,
  asserted the Debug action absent from expanded Buzz and Journal records, and
  reported zero visual, accessibility, overflow, page, or console findings.

## Seamless environment authorization and collapsed Repository cards — 2026-08-24

- Repository scenario cards start collapsed. Every card can be opened, closed,
  and reopened independently; the view does not force one card to remain open.
- One explicit sign-in requests the complete Companion intent: discover, read,
  operate, author, and publish. Auth Service narrows that intent to the
  authenticated user's current grant profile. Cleanup is never included.
- Commands never launch browser authorization. They reuse the VS Code Secret
  Storage session, refresh within 60 seconds of expiry, coalesce concurrent
  renewal, and retry one transient scheduled failure after 15 seconds. Only an
  explicit Sign in or Reauthorize action opens the browser.
- Access tokens live for 15 minutes. Rotating refresh tokens live for 30 days,
  are audience-bound, and are replaced on every successful refresh. Replay,
  revocation, expiry, client/resource mismatch, inactive users, and current
  grant reduction fail closed. Definitive rejection clears the local session;
  transient transport failure retains it for recovery.
- Stored session parsing accepts only a canonical declared scope profile and
  rejects malformed arrays, combined elements, unsupported scopes, widening,
  and non-rotating renewal state.

**Final evidence**

- Root Java reactor: 34 PocketHive modules plus the E2E and templating modules,
  `BUILD SUCCESS` in 2 minutes 32 seconds.
- Auth Service mutation: 117/117 changed-class lines covered and 45/45 mutants
  killed (`100%`, no uncovered mutation).
- VS Code unit/contract suite: 167/167 passed, including collapsed Repository
  state, browser-free commands, proactive/on-demand refresh, concurrency,
  transient retry, definitive invalidation, sign-out, and malformed storage.
- VS Code mutation: 2,722 killed plus 10 timed out across 20 production files;
  zero survivors, uncovered mutants, or errors (`100%`). OAuth/session parsing
  independently killed 493/493 mutants.
- Canonical `build-hive.sh`: Maven and image builds completed from clean
  artifacts. An unrelated local application already owned host port 8080, so
  startup used a temporary non-repository Compose override for WireMock's
  optional direct port (`127.0.0.1:18080`); PocketHive validation remained on
  the official `http://localhost:8088` ingress.
- Live Playwright/RST acceptance: authenticated `local-admin` against MCP
  `0.15.35`, discovered 54 tools, proved refresh-token rotation and replay
  rejection, projected a 289,449-byte owner payload to 3,869 bytes, captured 34
  auth/product/responsive states, and reported zero findings.
- Packaged and installed `pockethive.pockethive-vscode@1.0.5`.

final result: passed
