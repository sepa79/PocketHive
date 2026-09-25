# F06, F07 and F09 implementation handoff

Branch: `fix/f06-f07-f09-release`, based on
`474912ef8f817b750ec259bad719fbf6f207e57b` (PR #523 head, including #522).
Changes are uncommitted. This is local development evidence, not independent
acceptance, HiveGate execution or release approval.

## Delivered scope

- F06: worker profile loading/preparation, credential application and ordinary OAuth
  acquisition have focused owners. Existing signed OAuth and token coordination
  remain supported and tested with Redis and OpenSSL.
- F09: MCP application code consumes caller/client-interaction ports. Knowledge
  projection and invocation use the same caller scope decision; transport adapters
  decode/package requests and responses. MCP persistence is unchanged.
- F07: durable TCP workspace catalogue; server-owned default/deletion policies;
  browser state changes only after successful mutation; visible failures and reload.
  Existing UI style is retained. Catalogue selection provides no mapping/TCP isolation,
  tenancy or scenario/swarm/SUT linkage.
- TCP administration selects `NATIVE` or `POCKETHIVE` explicitly. Native needs no
  auth-service. PocketHive uses the existing identity client and browser session owner.
  Owner attribution is provider-qualified and persists across restart/provider changes.
  Authentication failure never switches provider or participates in TCP processing.

Canonical contracts and ownership:
[workspace](../../../tcp-mock/legacy-workspaces.md),
[responsibilities](../../../architecture/runtime-responsibilities.md),
[release priority](../../../inProgress/functional-module-boundaries.md).

## Verification

The initial full 50-module Maven reactor passed with real Redis, RabbitMQ and OpenSSL
fixtures (`/tmp/ph-release-reactor.log`). It preceded the final workspace/provider
changes. Those later changes were tested and packaged separately; this is not a
claim that the initial full reactor covered the final snapshot.

| Check | Observed result |
|---|---|
| Worker SDK | Initial 475 passed; review correction reran 15 AuthRuntime cases, including a new processor profile-discovery case |
| MCP | 253 tests, no failures/errors/skips |
| HTTP Sequence | 101 tests, no failures/errors/skips |
| Processor / Request Builder | 93 / 14 tests, no failures/errors/skips |
| TCP final package | 78 tests, no failures/errors/skips; executable jar produced |
| Auth-service mutation gate | 275/275 covered lines, 143/143 mutations detected |
| HTTP Sequence mutation gate | 145/152 covered lines, 84/84 mutations detected (one timeout) |
| MCP mutation gate | 2260/2260 covered lines, 869/869 mutations detected |
| TCP browser module tests | 20 passed (DOM/VM fixtures) |
| Deployed Playwright Chromium | 21 steps passed; zero uncaught page errors; zero axe violations in the scanned mappings state |
| PocketHive UI tests | 33 tests in 11 files passed after review correction |
| PocketHive UI builds | normal and VS Code plugin builds passed |
| VS Code extension | 187 tests and VSIX package check passed |
| HiveForge contract / Compose | contract check and configuration parsing passed |
| Final repository import boundary | 3 tests passed after all provider changes |

The browser module tests are not visual or accessibility acceptance. Mutation scores
apply only to each configured PIT scope. No thresholds were weakened.

Packaged process proof passed in both providers. Each run starts the TCP process three
times and forcibly kills it twice. Create/rename/delete, identity, owner, ordering and
default policy survive restart. Unauthenticated administration is rejected. Native
runs without an auth-service URL. PocketHive uses the real packaged DEV auth-service
and rejects Basic credentials. After stopping auth-service, a mutation returns 503,
the persisted catalogue is unchanged, and existing/new TCP connections still echo.
These prove process restart and authentication independence, not host power-loss,
production ingress, TLS or native TCP throughput qualification.

Commands for the final relevant checks:

```sh
./mvnw -q -f tcp-mock-server/pom.xml test package
./mvnw -B -ntp -f common/control-plane-core/pom.xml -Dtest=RepositoryImportBoundaryTest test
node --test tcp-mock-server/tests/ui/*.test.cjs
npm --prefix ui-v2 test
npm --prefix ui-v2 run build
npm --prefix ui-v2 run build:plugin
python3 tcp-mock-server/tests/workspace/restart_smoke.py \
  --jar tcp-mock-server/target/tcp-mock-server-0.15.35.jar \
  --output tcp-mock-server/target/workspace-native-provider-restart-proof.json
python3 tcp-mock-server/tests/workspace/restart_smoke.py \
  --jar tcp-mock-server/target/tcp-mock-server-0.15.35.jar \
  --auth-jar auth-service/target/auth-service-0.15.35-exec.jar \
  --output tcp-mock-server/target/workspace-pockethive-provider-restart-proof.json
bash tools/hiveforge-contract-check.sh
docker compose config --quiet
git -c core.whitespace=blank-at-eol,blank-at-eof,space-before-tab,cr-at-eol diff --check
```

The first final import-test invocation used `-pl`; repository `-am` caused an upstream
module to fail because it had no test matching that name. The module-POM invocation
above avoids selecting unrelated modules. No product test failure is excused by this.

[Verification snapshot](verification.json) records report totals, packaged proof and
log hashes. [Source snapshot](source-snapshot.json) hashes the changed implementation,
tests and contracts for review. Evidence files exclude themselves from that manifest.
Local full logs remain under `/tmp/ph-release-*`; they are not published here.

## Remaining acceptance and deferrals

- [Separate responsibility/SSOT review](separate-review.md) identified two medium
  findings and a stale documentation anchor. Corrections move all profile discovery
  into AuthProfileLoader and serve the canonical session module in Vite development.
  The new HTTP regression failed before the fix (HTML instead of JavaScript), then
  passed. AuthRuntime's 15 tests and UI's 33 tests, normal build and plugin build passed.
  Focused independent re-review resolved all three findings, independently confirmed
  JavaScript delivery, and matched all 99 source snapshot entries. Source review is
  supported; deployed browser checks are recorded in [browser verification](browser-review.md). Review is required by
  [the repository workflow](../../../ai/RESPONSIBILITY_WORKFLOW.md). Implementer
  verification is not that review. Review the actual paths, architecture and headers.
- Local Playwright now verifies shared login and native login against the deployed UI.
  Screenshots were inspected at desktop and narrow widths. The automated accessibility
  scan covers the visible light-theme mappings page; other modal/state accessibility,
  full keyboard journeys, other browsers and user acceptance remain outside this proof.
- Native TCP upgrade remains preserved in `feat/tcp-mock-upgrade` and its worktree.
  Advanced runtime activation, remaining scenario-state/public DTO work and final
  10,000 requests/sec with 60-second delay qualification are deferred, not claimed here.
  Integrate this release's workspace/auth owners into that branch deliberately; do not
  overwrite its native runtime/UI changes with whole-directory copies.
- Previously accepted inherited security findings, including the Artemis deferral,
  retain their earlier disposition. This boundary change does not close that security
  audit or claim its findings fixed.

No commit, push or release tag was performed.

## User testing deployment

At the user's subsequent request, the branch was copied into an isolated local
deployment at `/home/tim/.local/state/pockethive/release-manual-20260925/source`.
`build-hive.sh --quick` rebuilt the application services and UI; tests above had
already passed. The UI image initially failed because the docs-site include list
omitted the workspace contract. Its one-line registration was corrected and
independently reviewed; the actual Docker UI/docs build then passed.

Compose project/network `ph-release-manual-20260925` has separate retained data
volumes and scenario runtime storage. Existing port-8088 deployment was untouched.
PocketHive is at `http://localhost:18089`, TCP UI `/tcp-mock/`, TCP port 19090 and
TLS TCP port 19091. Native mode is at `http://localhost:18090`, TCP port 19092.
All published test ports bind loopback only.

Real ingress checks passed for health, JavaScript session asset, local-admin login,
trusted owner attribution, workspace create/delete and native login. Both plain
TCP listeners echoed successfully. Deployment evidence/configuration is retained
beside the source snapshot (`deployment.json`, `smoke.json`). Subsequent automated
checks and screenshot inspection are recorded in [browser verification](browser-review.md).

Manual testing reported missing mappings. Both new instances returned 26 mappings;
the observed browser entry `/tcp-mock` redirected to the old port 8088. An exact
relative redirect now preserves the public origin. The deployed regression
`tests/workspace/ingress_smoke.py --ingress http://localhost:18089` failed before
the correction and passed after rebuilding/redeploying the UI image. Authenticated
mapping retrieval still returns 26. Existing cached redirects can be avoided by
opening the full `/tcp-mock/` path. Logs: `/tmp/ph-tcp-ingress-redirect-{red,green,build}.log`.
