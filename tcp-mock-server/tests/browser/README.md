# TCP administration browser check

Runs Playwright Chromium against the isolated local release deployment. It uses the
existing pinned Playwright and axe dependencies from `vscode-pockethive`; install
that package's dependencies and Chromium before running on a fresh machine.

```sh
PH_UI_TEST_URL=http://localhost:18089 \
PH_NATIVE_TEST_URL=http://localhost:18090 \
PH_UI_TEST_OUTPUT=/tmp/ph-tcp-browser-check \
node tcp-mock-server/tests/browser/check.mjs
```

The PocketHive target must use DEV login (`local-admin`) and expose `/tcp-mock/`.
The separate native target must use its documented local `admin`/`admin` fixture.
Use only a test deployment: this check creates, edits and deletes uniquely named
workspace entries and mappings through the UI. It expects the shipped mappings,
including `echo-colon-format`, and leaves existing mappings untouched. A failed
mutation sequence can leave a `Playwright catalogue <timestamp>` or
`playwright-mapping-<timestamp>` test entry; inspect the failure report before
removing that specific entry.

The check covers login, ingress redirects, mappings, workspace success/failure
handling, tab navigation, desktop/narrow layouts and theme switching. Failures,
uncaught page errors and axe violations produce a nonzero exit. The axe scan covers
the stable light-theme mappings page, not every hidden screen or state. Screenshots
wait for transient notifications to disappear and disable animation during capture.
No session tokens, browser storage, HAR or traces are saved.
