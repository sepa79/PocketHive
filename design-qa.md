# PocketHive companion design QA

## Verdict

The implemented VS Code Side Bar matches the approved compact PocketHive
direction and preserves the restored product functions at narrow widths. The
sign-in and consent pages use the same brand system. No open P0, P1, or P2
visual defect remains in the sampled states.

final result: passed

## Review scope

The review compared each approved image and its implemented screenshot in one
combined image before judging differences:

| View | Approved source | Implemented state | Same-input comparison |
|---|---|---|---|
| Hive | `/home/tim/.codex/generated_images/019ff6b2-bbdb-75a0-8af0-bc8144c37a02/exec-9dc1087e-6601-40f6-b1f7-2ccf5e041126.png` | `vscode-pockethive/reports/playwright-ui/11-selected-hive.png` | `vscode-pockethive/reports/playwright-ui/comparison-hive.png` |
| Buzz | `/home/tim/.codex/generated_images/019ff6b2-bbdb-75a0-8af0-bc8144c37a02/exec-5944bfe8-28e1-47b9-8522-6f9eee06f9fc.png` | `vscode-pockethive/reports/playwright-ui/14-selected-buzz.png` | `vscode-pockethive/reports/playwright-ui/comparison-buzz.png` |
| Journal | `/home/tim/.codex/generated_images/019ff6b2-bbdb-75a0-8af0-bc8144c37a02/exec-1a9ac26e-e2fa-4a20-a3cc-3fdeed906b64.png` | `vscode-pockethive/reports/playwright-ui/13-selected-journal.png` | `vscode-pockethive/reports/playwright-ui/comparison-journal.png` |
| Scenarios | `/home/tim/.codex/generated_images/019ff6b2-bbdb-75a0-8af0-bc8144c37a02/exec-23405d5c-652c-4413-bccf-30518a8cec2f.png` | `vscode-pockethive/reports/playwright-ui/15-selected-scenarios.png` | `vscode-pockethive/reports/playwright-ui/comparison-scenarios.png` |
| Debug | `/home/tim/.codex/generated_images/019ff6b2-bbdb-75a0-8af0-bc8144c37a02/exec-9cb7e8d4-a35d-461a-964a-d3d736644d9a.png` | `vscode-pockethive/reports/playwright-ui/16-selected-debug.png` | `vscode-pockethive/reports/playwright-ui/comparison-debug.png` |

Auth and session states were also inspected in
`17-auth-sign-in.png`, `18-auth-consent.png`, `19-auth-consent-mobile.png`,
`05-workspace-needs-sign-in.png`, and `06-workspace-restoring-session.png` in
the same Playwright report directory.

## Findings and fixes

| Priority | Finding | Resolution |
|---|---|---|
| P0 | None | No blocking task, data-loss, or inaccessible-primary-action defect found. |
| P1 | None | No broken navigation, clipped primary action, or misleading session state remains. |
| P2 | Closed Account disclosure still occupied layout space because author CSS overrode native closed-state rendering. It overflowed at 140 CSS pixels and with a long principal at 280 pixels. | Explicit closed-state CSS removes the panel from layout. Account controls wrap and the workspace header stacks below 320 pixels. |
| P2 | Public-ingress refresh was redirected to sign-in because the Auth converter compared the forwarded-prefix request URI with the unprefixed token path. | The converter now derives one exact application path from the validated context path. Direct, prefixed, and inconsistent paths are tested; inconsistent paths fail closed. |

## Acceptance evidence

- Playwright exercised 20 environment, workspace, tab, account, sign-in,
  consent, narrow-width, and 200%-zoom-equivalent screenshots.
- The final report contains zero Axe, geometry, clipping, active-tab,
  keyboard-navigation, raw-owner-data, or session-flow finding.
- The 140, 240, 280, 320, and 480 CSS-pixel variations retain navigation and
  visible primary actions. Only the top tab strip may scroll at the narrowest
  widths.
- Hive exposes Start, Stop, guarded Remove, Debug, and collapsible run history.
  Buzz and Journal expose compact filters. Scenarios and grouped Debug preserve
  the approved one-column Side Bar hierarchy.
- The authenticated workspace and last good owner data remain rendered during
  session restore or renewal. Sign-in, retry, and sign-out are available from
  one Account menu without a page flash or forced navigation.
- The Auth pages use the canonical PocketHive logo, token colours, focus
  treatment, responsive spacing, and explicit client/resource/permission
  context without changing OAuth fields or behaviour.

This is local visual and interaction evidence. Native screen-reader testing,
remote identity-provider variation, and governed production deployment remain
separate release activities.
