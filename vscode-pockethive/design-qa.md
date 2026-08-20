# PocketHive Companion design QA

**Evidence**

- Source visual truth: `/home/tim/.codex/generated_images/019ff6b2-bbdb-75a0-8af0-bc8144c37a02/call_AjFUMdmJYCZ7KVi0k4EhrMuL.png`
- Browser-rendered implementation: `reports/playwright-ui/11-selected-hive.png`
- Side-by-side normalized comparison: `reports/playwright-ui/design-qa-hive-comparison.png`
- Supporting states: `reports/playwright-ui/12-selected-hive-history.png`, `13-selected-journal.png`, `14-selected-buzz.png`, `15-selected-scenarios.png`, and `16-selected-debug.png`
- Viewport: 428 × 917 CSS px, dark theme, Hive selected, authenticated local environment, account menu closed, run histories collapsed.
- Source pixels: 853 × 1844. The source was proportionally normalized and cropped to 428 × 917 for comparison.
- Implementation pixels: 428 × 917 at Playwright's default device scale factor 1. CSS size and captured pixels are therefore 1:1.

**Findings**

- No actionable P0, P1, or P2 difference remains.
- Typography: the implementation preserves the reference's compact hierarchy, readable small text, restrained weights, one-line tab labels, and single-line Buzz/Journal event identities. It uses VS Code-native font tokens so it remains legible and visually native across host platforms.
- Spacing and layout rhythm: the compact environment header, five-tab rail, controls, and swarm cards match the narrow sidebar composition. Controls remain within the viewport at 140, 240, 280, 320, and 480 px checks; no document overflow, accidental clipping, or undersized controls were reported.
- Colors and visual tokens: the near-black/navy surface, cyan actions, green connected state, subdued borders, and PocketHive yellow active-tab token preserve the approved theme and semantic contrast.
- Image and asset fidelity: the repository-owned PocketHive hexagon asset is used for swarm identity. No logo or product imagery is approximated with emoji, CSS art, or a replacement inline drawing.
- Copy and content: labels are concise at sidebar width. `Start` and `Stop` appear only on the current live swarm card and are derived from owner lifecycle state. Historical run rows expose only `Open journal`, so an old run cannot be restarted.
- Intentional differences from the source: the later approved design removed `Profile` from the account menu. The user's lifecycle correction also replaces source run-history play affordances with exact Journal evidence links. Runtime names and counts come from the MCP-backed test environment rather than visual-only fixture parity.

**Focused region comparison**

- Header and tabs were inspected in the full comparison at readable scale; back navigation, environment name, connection state, account control, and all five tab labels align without wrapping.
- Live swarm cards and the expanded run-history state were inspected in `12-selected-hive-history.png`; current Start/Stop actions and the history-only `Open journal` action are visually and semantically distinct.
- Buzz and Journal were inspected in `13-selected-journal.png` and `14-selected-buzz.png`; event identity remains one line and advanced fields remain behind the Filters disclosure.
- Scenarios and Debug were separately inspected because their dense file/profile and diagnostic controls cannot be judged reliably from the Hive full-view comparison.

**Interaction and accessibility evidence**

- Primary interactions tested: account menu, keyboard and pointer tab navigation, live-swarm Start and Stop messages, run-history expand/collapse, exact-run Journal navigation, advanced filter disclosure, searchable exact-swarm selection, and all five tab states.
- The browser check exercised the real local MCP and OAuth pages, including authentication refresh/replay behavior.
- Axe checks, responsive geometry checks, and browser console-error collection completed with zero findings across 21 screenshots.

**Comparison history**

- Initial implementation review found the global product masthead consumed excessive vertical space and the desktop-oriented controls did not fit the sidebar target. The masthead was removed and the shell was condensed around the environment context.
- Subsequent review found lifecycle actions and run history needed clearer ownership. Start/Stop moved onto the eligible current swarm card, while historical runs became read-only Journal links.
- Post-fix evidence is the normalized comparison plus the expanded history and tab screenshots listed above. No actionable P0/P1/P2 item remains.

**Implementation Checklist**

- [x] Compact authenticated environment shell on every workspace tab.
- [x] Context-valid live-swarm Start/Stop actions.
- [x] Read-only historical runs with exact Journal navigation.
- [x] One-line Buzz and Journal event identity with collapsible advanced filters.
- [x] Searchable exact-swarm controls.
- [x] Responsive, accessibility, interaction, console, and live-MCP checks.

**Follow-up Polish**

- None required for this handoff.

final result: passed
