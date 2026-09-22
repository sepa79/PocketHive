# Intake, explicit HTTP and Artemis integration — 2026-09-22

The integration branch is `merge/intake-http-artemis`. PR #519 is the first-parent
baseline; the incoming branch and intake draft adapt to its contracts. This record
resolves the [earlier review](pr-review-current-and-519-2026-09-22.md), preserving
that report as the history of reproduced failures.

## Sources and retained behavior

| Input | Revision / scope |
| --- | --- |
| Target PR #519 | `18987afc959171324ed80d71026d888e181ddabc`; its only change since reviewed `79a7a3c1` is a changelog update |
| Incoming branch | `d77dd1cec2190a801efd55846280ed6b4355e290`; authorized intake and explicit remote-HTTP merges plus tooling advisory fixes |
| Intake draft | 1.9.0 canonical runtime vocabulary projection, result identity checks and separate qualification workflow |

The merge preserves explicit WorkPlane selection, Rabbit CONTROL, Artemis WORK,
delayed source delivery, managed Artemis state and stop-first deployment updates.
Both HiveForge actions retain proxy placement, the HTTPS listener and MCP temporary
storage. The incoming shared endpoint resolver still requires explicit remote HTTP
opt-in. Product Maven builds do not depend on the portable intake skill files.

## Review findings resolved

| Original finding | Change and observable result |
| --- | --- |
| Inherited Decline could issue a code | The consent customizer runs after Spring validates the pending request. Explicit Decline clears the complete proposed grant, including prior consent; Spring owns revocation, pending-state consumption and denial. |
| Incoming handler replaced valid denial callbacks with HTML 400 | The failure owner projects only Spring's validated callback and original client state. Unsafe or unvalidated requests retain bounded local errors. |
| #519 delayed diagnostic copies bypassed ring capacity | The broker transformer removes scheduling from the divert's independent copy. The diagnostic ring receives the copy immediately; source timing and WorkItem bytes are unchanged. |
| Incoming OAuth types lacked ownership records | Canonical RESP-OAUTH records now identify registry, persistence, registration, authorization input, consent, callback, page and composition owners; affected headers link to them. |

Artemis now requires the release-matched PocketHive image with its small Java 21
broker extension. Local build, remote-image tooling, publisher matrix, release
inventory and both deployment templates include it. The JAR lives in the broker
distribution library directory, outside the persistent instance mount. Missing
extension classes fail tap creation explicitly. No polling or alternate adapter
was introduced.

## Separate responsibility review

Independent agents reviewed implementation they did not author, following the six
passes in `docs/REVIEW_RULES.md`. The bounded source reviews have no remaining
blocking findings; the release-inventory and image-documentation omissions found
during review were corrected and separately rechecked.

| Canonical responsibility | Owner, consumers and effects inspected | Separate evidence |
| --- | --- | --- |
| `RESP-OAUTH-CONSENT-ACTION` | Enum owns exact action values; renderer and validated Spring consent customizer consume them. Spring alone mutates consent/authorization stores and issues codes. Existing grants are merged before the customizer and removed on denial. | Reviewer inspected actual Spring Authorization Server 1.5.8 sources, action consumers and store writers; approval, checked-scope decline, prior consent, malformed actions and replay regressions pass. |
| `RESP-OAUTH-BROWSER-FAILURE` | Failure handler receives Spring's validated exception token; raw request redirect/state do not establish trust. Encoded original state and existing callback query are preserved; error codes are bounded. | Provider/validator exception paths and consuming configuration inspected; invalid client/state/redirect and valid denial paths covered. |
| Other changed `RESP-OAUTH-*` records | Registry is the client-state authority; JSON persistence owns file IO; registration, loopback adaptation, authorization conversion, rendering and composition remain separate owners. | Repository-wide owner searches and actual consuming paths checked against architecture/header records. No second consent parser, pending-request validator or store introduced. |
| `RESP-ARTEMIS-CAPTURE-TRANSFORM`, `RESP-WORK-ARTEMIS-TRANSPORT` | Native broker divert creates the independent copy; transformer clears only its scheduled-delivery metadata; adapter owns bounded ring/capture resource lifecycle. | Embedded and packaged-broker regressions verify capture capacity, unchanged source contents/count and no early source delivery. |
| `RESP-PUBLIC-ENDPOINT-TRANSPORT`, HiveForge deployment/image contracts | One shared input resolver and action fact project endpoint policy and DEV fixture credentials; image manifest feeds existing builds, with publisher/release inventories as documented projections. | Separate source review traced both actions, nested JAR staging, broker library/state paths and template effects; render matrix and inventory recheck pass. |
| `RESP-INTAKE-RUNTIME-VOCABULARY`, `RESP-INTAKE-RESULT-EVIDENCE` | Compiled runtime contract owners feed the sealed packaged vocabulary; intake readiness and unexecuted result identity remain separate. | Fresh offline qualification, vocabulary drift check and skill-masked product tests; copied skill and extracted package remained byte-identical during qualification. |

Searches followed actual constructors, store writers, provider hooks, image-manifest
consumers and deployment branches. No custom architecture scanner or bean-selection
test was added. The existing import test and Maven dependency bans retain their
limited declarative role.

## Validation

- **Java reactor:** 2,578 tests across 437 suites, zero remaining failures/errors/skips.
  `./mvnw -B -ntp install` passed every preceding module, then exposed the
  acceptance timing fixture described below. After its test-only correction,
  `./mvnw -B -ntp install -rf :acceptance-tests` passed all 178 acceptance tests
  and completed the final module. Disposable Redis/Rabbit fixtures and the
  configured OpenSSL oracle were enabled.
- **Auth PIT:** 537/537 covered lines and 283/283 killed mutations; the new
  consent boundary is included in the unchanged 100% thresholds.
- **HTTP Sequence PIT:** 145/152 covered lines (95%), 84/84 killed mutations;
  configured thresholds unchanged.
- **VS Code:** 195 tests passed, assets/cutover checks passed; package contents
  check passed and npm audit reported zero vulnerabilities.
- **Intake:** 265 offline tests passed, plus package integrity, five source
  checksums, canonical vocabulary drift check and 74 Java tests with the skill
  directory masked. ZIP SHA-256:
  `9c842c5d4d10571a05dee8b3295e920ba06bfaabc6530338762238af547b7f01`.
- **Artemis:** 44 focused tests passed (39 adapter, two extension and three import-boundary tests). The built shipping image passed both
  immediate/delayed five-message capture cases: two retained copies, all five
  source messages intact, no early source delivery. The probe's client classpath
  deliberately excluded the extension JAR.
- **Deployment/build:** four render tests, HiveForge contract checks, image
  selection dry-run, shell syntax and whitespace checks passed. Workflow syntax
  passed actionlint with its optional ShellCheck integration disabled; enabling
  it reports the same inherited SC2129 style suggestion in the untouched version
  script on both target and candidate.

The root run exposed a target fixture's cold HTTP exchange exceeding its 250 ms
operation budget before reaching the polling path. Its test now allows a 2 s
operation budget with a longer 4 s poll, keeps the exact timeout/correlation
assertions and verifies recorded DISPATCHED evidence. Only the fixture changed;
the independent review confirms it still detects an extra request or renewed budget.

The first full VS Code mutation run scored 99.93%, exposing two unasserted
`redirect: 'error'` values in archive PUT and session DELETE. New real HTTP tests
complete the MCP handshake, reject 307 responses and prove the destination is
never requested; existing request-contract tests also assert the policy. No
client behavior, mutation target or threshold was changed.

MCP mutation baseline exposed another inherited fixture race: cleanup joined its
helper before clearing the intentionally preserved interrupt flag. A bounded
reproduction captured `InterruptedException` at that join. Cleanup now clears the
flag after the behavior assertions and before joining; production behavior,
assertions and mutation settings are unchanged. Separate review evidence is in
`final-fixture-independent-review.md` and `acceptance-budget-independent-review.md`.

The final full VS Code mutation gate passed at **100%**: 2,885 killed,
10 timed out, zero survived, zero uncovered and zero errors (eight existing
ignored mutations). All 22 configured source targets and the 100% threshold were
retained. The final run used eight workers; the initial run used four.

The final full MCP mutation gate also passed: **2,136/2,136 covered lines** and
**858/858 detected mutations** (854 killed, 4 timed out), with no survivors
or uncovered mutations. All configured Java and VS Code mutation gates passed
without lowering thresholds or adding exclusions.

## Evidence and limits

Local evidence directory:
`/home/tim/.tmp/pip/pockethive-integration-fkkw2gzh`.
Separate reports: `oauth-independent-review.md`, `artemis-independent-review.md`
and `deployment-image-source-review.md`. Intake evidence is under
`intake-qualification-w2ekpmcc`; broker proof includes `artemis-container-summary.json`
and its retained probe source/log. These are local development proof, not governed
HiveGate execution or live HiveForge acceptance. No full Swarm deployment, browser
session or hosted CI run is claimed for this integration candidate.

The earlier review's HFM limitations remain: HiveMind is advisory under the lab
exception; authenticated HiveGate execution and a resolved HiveMap workspace were
not available. No global configuration was changed and nothing was pushed.
