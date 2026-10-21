# UI V2 Control-Plane Follow-ups

> Status: future / design
> Delivered baseline: `docs/archive/ui-v2-control-plane-adoption.md`

- [x] Reconcile initial swarm hydration with the current Orchestrator state API.
  `RESP-UI-SWARM-DETAIL` documents the single baseline and its UI regression tests;
  the raw status-full wrapper is no longer accepted by Hive detail hydration.
- [ ] Add the remaining manual multi-instance verification through official ingress/API paths.
- [ ] Decide whether debug-only raw work-exchange subscription remains a product feature; do not enable it implicitly.
- [ ] Add Wire Log keyboard navigation if still desired.

Current topology and live-config identity rules live in `docs/ARCHITECTURE.md`, `docs/ORCHESTRATOR-REST.md`, and `docs/ui-v2/UI_V2_FLOW.md`.

## Selected swarm after REMOVE — 2026-10-21

Status: recorded only, P2; explicitly deferred by the human while preparing
the Pocket-family integration release.

- [ ] After the selected swarm's canonical REMOVE operation succeeds, return
  to the Hive list and release its detail selection. An accepted request alone
  is not removal success; retain actionable feedback while removal is pending
  or fails.
- [ ] Stop snapshot, journal and network-binding polling for the removed
  selection. Do not leave `/hive/{swarmId}` issuing repeated404 requests.
- [ ] Keep backend lifecycle/operation state authoritative. The UI owns only
  navigation, selection and read projections; do not introduce another swarm
  lifecycle owner. A late removal response must not clear a different selection
  or a replacement run.
- [ ] Cover successful removal from the detail page, pending/failed removal
  and navigation or run replacement while the request is outstanding with UI
  regressions and a real-browser check through the official ingress.

Evidence: local integration E2E created `ui-e2e-20261021` through the UI,
started it, changed generator/processor configuration, stopped it and removed
it. The row disappeared, but the URL remained on its detail route and snapshot,
journal and network-binding requests continued returning404 until navigation.
The runtime was removed correctly; this is stale view selection, not another
authoritative runtime state store.

## Reported in release 0.15.37 — 2026-10-21

Status: recorded only; defer implementation to the PocketData/Pocket-family
integration work (current branch: `codex/pocketplanner-integration`). Do not patch
the running release or its clean `main` checkout for these follow-ups.

### Remove-swarm confirmation layout

- [ ] Remove the duplicate text `Cancel` action: the screenshot shows one in the
  header and another beside `Remove`. Keep one `Cancel` and one explicit,
  destructive `Remove` confirmation action.
- [ ] Make this confirmation a compact, centred, content-sized dialog. It must
  not stretch across the viewport or reserve a large empty area. Retain sensible
  viewport margins and bounded overflow on smaller screens.
- [ ] Verify the rendered dialog and shared dialog styles in the integration
  branch; reuse the shared UI owner rather than adding another modal layout.

Evidence: human screenshot of `/hive`, `Remove swarm` confirmation, attachment
`codex-clipboard-b2d52fcd-6e65-4943-89b1-e803efe0f607.png`.

### Failed startup has no actionable explanation

- [ ] Preserve and expose the actual startup failure instead of presenting only
  `FAILED`. The local release used ARTEMIS while the selected RabbitMQ examples
  required Rabbit settings; the controller logged
  `outputs.rabbit: Selected settings block is required`.
- [ ] Carry a bounded, safe failure code/message and relevant configuration path
  from the canonical runtime failure owner through the operation/status
  projection to the UI and journal. Preserve the run/correlation identity; do
  not invent another diagnostic owner or make the UI parse container logs.
- [ ] Review the public contract before implementing that propagation. Do not
  expose credentials, request payloads or raw stack traces in user messages.
- [ ] Verify that a rejected startup shows its reason in the normal operator
  flow, not just in developer logs, without retrying the failed action.

Evidence: `dsffvsvs` CREATE operation
`a653e222-5a38-4615-ba30-5c41d57b9a7d` returned `FAILED` with controller state
and artifact digest but no failure reason; its controller log contained the
configuration error above. This record does not claim the UI or runtime has
been fixed.
