# Current branch and PR #519 review — 2026-09-22

**Follow-up:** all four findings were fixed and independently reviewed in
`merge/intake-http-artemis`; see the [integration and validation record](intake-http-artemis-integration-2026-09-22.md).
The original findings and failed reproductions below remain historical evidence.

**Recommendation: fix the findings before combining.** PR #519 remains the target
baseline. Its new Artemis capture implementation has a reproduced bounds defect;
the current branch introduces an OAuth cancellation regression. A separate,
inherited consent defect affects both trees. Passing existing checks does not
cover these cases.

## Scope

| Source | Reviewed revision / scope |
| --- | --- |
| Base | `6acad58ef85d09ba856adb1c56143aa01ab3ce0c` (`origin/main`) |
| Target [PR #519](https://github.com/sepa79/PocketHive/pull/519) | `79a7a3c1b3d3c3ff96236120ae9b0d8178a1ea3c`; Artemis WorkPlane, delayed delivery, acceptance framework, deployment and export changes |
| Current local branch | `d77dd1ce`, `fix/vscode-tooling-advisories`; includes the authorized intake and explicit remote-HTTP merges |
| Current draft | Uncommitted intake 1.9.0 schemas, canonical vocabulary exporter, readiness/reference/result checks, dedicated workflow and Maven isolation |
| Published [PR #518](https://github.com/sepa79/PocketHive/pull/518) | `495028e8fab2ba75847ec944bb717b2d75ad2351`; its passing checks do not cover the later local merges or draft |

The review used independent runtime and deployment/acceptance review tasks,
source inspection, repository-wide owner searches and focused reproductions.
No implementation fixes, commits, pushes or remote PR comments were made.

## Findings

### P1 — inherited: Decline can approve the requested OAuth scopes

`auth-service/src/main/java/io/pockethive/auth/service/oauth/OAuthBrowserPageRenderer.java:65`
submits `consent_action=cancel` while the checked `scope` inputs at line 78
remain part of the form submission. No application converter consumes
`consent_action`. The authorization server therefore processes the submitted
scopes as approval and returns an authorization code.

An official MockMvc authorization/consent sequence, using browser Accept headers,
an isolated authorization context, valid CSRF, the pending consent state and the
fields sent by the rendered Decline button reproduces this on both reviewed
trees. The returned callback contains a code instead of `error=access_denied`.
The renderer and converter already have this behavior on `origin/main`: this
is **not introduced by #519**. It nevertheless prevents an unqualified claim
that the target baseline is correct. Handle decline through the canonical
consent boundary and verify that no authorization code or consent is granted.

### P2 — current branch: preserve validated OAuth cancellation redirects

`auth-service/src/main/java/io/pockethive/auth/service/oauth/OAuthBrowserAuthorizationFailureHandler.java:39-43`
always converts authorization failures to HTTP 400 HTML. The new registration
in `PocketHiveOAuthConfiguration.java:198` replaces the authorization server's
error response handler, including its safe callback for rejected consent.

For a valid pending authorization followed by a consent POST with no selected
scopes, #519 returns the expected callback with `error=access_denied` and the
original client state. The current branch returns HTTP 400 without that callback,
leaving the client waiting for authorization until it times out. The same
public-interface regression test passes on #519 and fails on the current branch.
Preserve validated error redirects; use the themed local error page when the
request cannot safely redirect. Carrying the current behavior into #519 would
violate the user's no-regression requirement.

### P2 — PR #519: scheduled diagnostic copies bypass the capture item limit

`common/artemis-adapter/src/main/java/io/pockethive/artemis/work/ArtemisWorkDebugTap.java:53-57`
creates a ring queue and a native divert that preserves the Work message's
scheduled-delivery metadata. The ring size bounds immediately queued messages,
but does not bound the broker's scheduled-message store.

A real embedded Artemis probe uses the actual WorkPlane/output/debug-tap owners,
`maxItems=2`, `ttlSeconds=30`, and five messages. Its immediate-delivery control
retains two capture messages. With `delayMs=180000`, the capture retains five
at every observation from 250 ms through 2.5 seconds; the bound assertion fails
and the probe exits 1. Both source queues retain all five messages. Under delayed
load the diagnostic copy can grow beyond its declared limit, and polling cannot
drain it before scheduled delivery. Bound the diagnostic copy independently of
source scheduling, preserving the source's delay and delivery policy, and cover
this combination with an adapter regression test. No TTL defect is claimed by
this short observation window.

### P2 — current branch: establish the new OAuth responsibility records

The new `JsonFileDynamicClientStateStore.java:17`,
`DynamicClientStateStore.java:8`, `DynamicClientStateEntry.java:9`,
`DynamicClientStateDocument.java:8`, `LocalhostLoopbackRedirectValidator.java:14`
and `OAuthBrowserAuthorizationFailureHandler.java:22` cite only the API spec in
their headers. They lack the required stable responsibility ID and architecture
section link, and repository-wide documentation searches found no ownership
records naming these new implementations. The base branch already requires this
evidence in `docs/ENGINEERING_RULES.md` and `docs/ai/RESPONSIBILITY_WORKFLOW.md`.
The behavior description in the API spec does not establish the current owner,
ports, consumers and prohibited effects required by that workflow.

Add the canonical records for registry state/persistence, redirect adaptation
and browser authorization failure ownership, then align these new headers.
This is a review-acceptance gap, not proof of a second active runtime authority;
untouched inherited headers are excluded. Independent evidence is retained in
`current-oauth-responsibility-review.md` in the local evidence directory.

## Ownership and six review passes

| Responsibility / contract | Owners and effects inspected | Verdict / evidence |
| --- | --- | --- |
| `RESP-WORK-ARTEMIS-TRANSPORT`; runtime responsibilities and WorkPlane boundaries | Artemis WorkPlane composition, sessions, input settlement, output scheduling, topology/resource operations and debug taps; consumers use selected WorkPlane ports | Capture bound violated as above; no other confirmed introduced defect in the reviewed runtime paths |
| `RESP-WORK-DELIVERY`; WorkPlane boundaries §12 | `WorkDeliveryParser` owns declarations/defaults; configuration and startup consume it; result publication passes delivery intent to the selected output | No competing policy owner established; admission-time ACK and source scheduling are documented intended behavior |
| `RESP-WORK-TRANSPORT`, `RESP-WORK-CONTEXT` | SDK executor admission/rejection, disable/re-enable and accepted-task behavior; executing identity and accepted history-policy snapshot | Traced effects and state transitions; no confirmed introduced defect in this scope |
| Runtime filesystem/export records | `RuntimeFilesystemLayout` owns swarm/run/worker output paths; `RuntimeOutputDirectory` confines relative names; clearing-export sink performs writes; REMOVE retains cleanup ownership | No new competing resolver or introduced defect established |
| Acceptance architecture | Target loader → ingress HTTP/API facades → operation observers and exact resource handles; evidence and sample retention have separate owners | No introduced defect established; no fresh live acceptance claim |
| HiveForge deployment contract | Shared Swarm preparation, render template, explicit WorkPlane inputs, TLS assets, proxy placement and MCP temporary storage | Source review and static contract check passed; actual combined deployment remains unverified |
| Auth Service API §6.10 | Browser form/controller/converter → Spring Authorization Server → selected failure handler | Inherited approval-on-decline and introduced cancellation callback regression reproduced separately |
| `RESP-PUBLIC-ENDPOINT-TRANSPORT` | Shared Java endpoint policy; companion transport mode owner; profile/webview consumers; shared HiveForge public endpoint resolver | Explicit HTTP opt-in retained; no new transport-policy defect established |
| `RESP-INTAKE-RUNTIME-VOCABULARY`, `RESP-INTAKE-RESULT-EVIDENCE` | Compiled `AuthType`/`RequestTemplateProtocol` → exporter → packaged schema resolver; intake readiness remains separate; result identity never infers execution | No additional finding in draft. Snapshot integrity verified; dedicated workflow owns drift checks, ordinary product Maven tests do not |

Repository searches included alternative WorkPlane/resource/debug-tap owners,
delivery parsers and output publishers, history-policy readers/writers,
filesystem output resolvers, `consent_action` consumers, registered-client and
failure-handler composition, and intake enum/protocol/auth consumers. Source
review followed constructors and selected consumers, not class names alone.
No custom architecture scanner or bean-selection test was added.

| Pass | Result |
| --- | --- |
| Plan | Combining is deferred until target and incoming defects are corrected; #519's runtime/deployment semantics are the baseline to preserve |
| Style | Blocked by missing canonical records/links for the six new OAuth types; reviewed module boundaries and remaining named owners |
| Conciseness | No additional competing implementation found in the reviewed paths; avoid a second consent parser or intake runtime contract |
| Security | Blocked by inherited consent behavior; incoming cancellation handling also violates the documented OAuth boundary |
| Library necessity | Existing Spring authorization machinery, Artemis client/BOM and JDK acceptance/export tooling remain the intended facilities; no new library proposed |
| Maintainability | Separate source, target and inherited findings; preserve observable callbacks and capture limits with focused tests |

## Validation and limits

Final browser probes are retained as `ReviewConsentDeclineTest.java`,
`pr519-final-consent-probes.log`, `current-final-consent-probes.log`, and the
corresponding result text files in the local evidence directory below. Target:
two probes, one inherited-defect failure and one pass. Current: two probes, both
fail for the separately described behaviors. An earlier combined probe shared
consent state; final probes isolate the context for each test and supersede it.

The Artemis probe and failing bounded-capture evidence are retained as
`artemis-CaptureDelayProbe.java` and `artemis-output.log`. Its immediate control
and unchanged source counts distinguish the diagnostic defect from source loss.

The target HiveForge contract check passed. Current draft `verify-package`
passed for version 1.9.0 and 200 sealed files; `git diff --check` passed. Earlier
265-test offline intake qualification and 74-test product-build isolation
evidence remain applicable historical evidence for the unchanged draft; those
suites were not counted as fresh review executions.

Fresh target reactor validation passed: **1,965 passed, four skipped**, zero
failures/errors across 341 reports, including 178 acceptance-framework tests.
Command:

```sh
AUTH_OPENSSL_TEST_EXECUTABLE=/usr/bin/openssl ./mvnw -B -ntp \
  -pl common/artemis-adapter,common/worker-sdk,swarm-controller-service,orchestrator-service,clearing-export-service,acceptance-tests \
  -am test
```

The skipped tests are the two external Redis auth tests and two external Rabbit
Swarm lifecycle tests; those fixtures were not configured locally. Other broker
component tests used the repository's test fixtures. The first invocation
stopped because its independent OpenSSL oracle variable was missing; the rerun
corrected that harness setting. Results and skip names are in
`pr519-reactor-summary.json`; the successful log is `pr519-reactor-openssl.log`.

The [hosted target run](https://github.com/sepa79/PocketHive/actions/runs/35725765099)
reports successful Java tests, Java mutation gates and Windows extension checks.
The Linux extension job was still running at the last review check. Hosted
results are distinguished from commands executed locally. GitHub reported the
PR merge state as BLOCKED; this review did not change protection or approvals.

No remote Swarm acceptance, native-client remote-HTTP flow, combined deployment
or full hosted gate for the local draft was run. Existing green suites do not
overrule the reproduced failures.

## Integration disposition

Created local branch `review/intake-http-artemis` at the exact #519 target head in
`/home/tim/IdeaProjects/PocketHive-integration-review`. It is an unchanged target
checkout, **not a combined branch**. No merge is pending and no merge commit was
created. The original branch, its draft, stash and cleanup backup were preserved.

A target-first `git merge-tree --write-tree 79a7a3c1 d77dd1ce` reports two textual
conflicts: `deploy/hiveforge/components/stack/ansible/swarm-stack.yml` and
`docs/HIVEFORGE.md`. These are resolvable, but textual mergeability is not a
behavioral acceptance result. The uncommitted intake work remains separate.

After fixes, preserve #519's explicit WorkPlane selection, Artemis state/service
configuration, admission and delayed-delivery behavior, proxy placement, TLS
listener/assets, MCP temporary storage and acceptance/export contracts. Adapt
the incoming public-endpoint/auth configuration to these owners, update its
render fixtures for the target's work/proxy inputs and filters, and verify both
deploy and update. Then apply the reviewed intake draft and repeat the relevant
combined checks. A new commit would still require an explicit human commit
request under AGENTS.md; none was sought because the integration is not ready.

## Local evidence and HFM

Evidence directory:
`/home/tim/.tmp/pip/pockethive-pr-review-w_bvjue7`.
It includes source revisions, the preserved draft patch/untracked-file hashes,
target-first merge output, focused reproductions, and the independent deployment
review. Raw logs are local development proof, not governed execution evidence.

HFM's existing `swarm-manager` VM was started with its documented lab command.
HiveMind MCP subsequently reported healthy; PocketHive was resolved and a review
session opened through the permitted advisory HFM exception. Memory reminded the
review to distinguish browser HTML from machine responses and that prior consent
checks covered happy paths/CSRF rather than Decline. Current source and probes
remain authoritative. HiveGate requires authenticated access unavailable to this
protocol client; no authentication was bypassed or global configuration changed.
HiveMap's configured stdio MCP connected, but its catalogue exposes no workspace
resolver/list tool and requires an unresolved `workspaceId` for graph reads.
No workspace was guessed, no scan was started and no graph evidence is claimed.
HiveMind captured the report and all four findings as **open risks**. Review-only
sessions `sess-3f05f03db4fb51bf9c4c33d96427fce7` and
`sess-e8086e4bcb375ddd3127d8586f220fe2` closed successfully with no missing required
rule checks; this records completion of the review, not resolution of defects or
approval to merge. The final artifact reference is
`ent-7e12a6a4075cfde3c17800b369529383`. MCP evidence is retained in
`/tmp/pockethive-hfm-review-zTgFdr`.

The read-only global registration check also found `node_repl` and `cua_repl`
outside the listed direct operational exceptions. They were neither used nor
changed; the review does not treat them as governed capability paths.
