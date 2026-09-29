# Deployed TCP administration browser verification — 2026-09-25

**21 Playwright steps passed, with zero uncaught page errors.** The axe scan found
zero violations in the stable light-theme mappings page. The 20 Node UI tests also
passed. This is local development evidence, not release approval or a guarantee
that every UI state is defect-free.

The test used isolated headless Chromium contexts, the real PocketHive DEV login
at `http://localhost:18089`, and the standalone native login at
`http://localhost:18090`. No VS Code browser setting or desktop Computer Use was
required. The deployed TCP jar was rebuilt with `build-hive.sh --quick --service
tcp-mock-server`; the TLS and native instances were refreshed from that image.
All 19 deployment services were running and configured healthchecks healthy.

| Area | Evidence |
|---|---|
| Authentication | PocketHive login, same-origin `/tcp-mock` redirect, mapping load; native login and logout |
| Mappings | Search canonical request pattern, both Pattern sort directions, editor cancel, create/edit/delete |
| Workspace catalogue | Create/select/rename/reload/delete; intercepted HTTP 503 for create, rename and delete preserves form/catalogue/selection |
| Navigation | Dashboard, scenarios, verification, test, settings, docs and mappings tabs become visible |
| Layout | 1920×1080 baseline, 1366×768, 768×900 and 320×900; no root horizontal overflow at checked narrower widths |
| Theme/accessibility | Dark theme switching and screenshot; WCAG 2 A/AA and 2.1 AA axe rules on stable light mappings state |

Browser and screenshot inspection led to these scoped fixes:

- Search and priority warnings read `requestPattern`, matching the API. Previously,
  search threw and absent `pattern` values produced false conflicts between mappings.
- Pattern sorting now uses the same field. Priority warnings remain advisory
  heuristics, not an authoritative regex-overlap or runtime-matching decision.
- Mappings load immediately when opening their tab.
- Workspace actions preserve the dropdown when a clicked row is rebuilt during
  request handling. Failed creation shows its retained-input message above the action
  buttons, avoiding button distortion.
- Header/actions wrap on narrow screens; navigation/table scrolling stays contained.
  Secondary header status/search controls appear at desktop width to avoid tablet
  title clipping; the browser check now verifies the title fits inside the header.
  Controls have accessible names, the warning region is keyboard-focusable, and
  observed text contrast failures were corrected without changing the established style.

[Machine-readable result](browser/report.json),
[desktop](browser/02-mappings-desktop.png),
[1366 px](browser/05-mappings-1366.png),
[320 px](browser/05-mappings-320.png),
[failed creation](browser/03-workspace-failure.png),
[mapping editor](browser/04-mapping-editor.png),
[dark theme](browser/07-dark-mappings.png),
[native mode](browser/06-native-mappings.png).

Reproduce with [the browser harness instructions](../../../../tcp-mock-server/tests/browser/README.md).
The successful run is `/tmp/ph-tcp-playwright/tablet-final-run.log`; earlier failing runs
are retained locally. One intermediate contrast scan caught the theme transition
rather than its settled state; the runner now waits for the light background before
scanning. Failures and nonempty axe violations produce a nonzero exit.

The scan does not cover every modal, dark-theme accessibility, full keyboard journeys,
other browsers, advanced TCP functionality or load qualification. Tab visibility is a
navigation smoke check, not full feature acceptance. Below 1080p remains best effort;
the table and navigation use horizontal scrolling on small screens. Test-created
records were deleted through the UI. No credentials or browser sessions are included
in these artifacts.

The source snapshot preserves `index.html`'s existing CRLF endings, keeping the diff
focused. Whitespace verification uses Git's `cr-at-eol` handling while retaining
blank-at-EOL, blank-at-EOF and space-before-tab checks. No global config was changed.
Existing direct browser MCP registrations were not used as governance authority;
all results here are local test evidence.
