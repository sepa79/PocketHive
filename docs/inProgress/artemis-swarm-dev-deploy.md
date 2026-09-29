# Artemis development deployment on the large Swarm

Status: freshly redeployed on 2026-09-22; all 18 services at 1/1, smoke and SM-2 PASS.
Full Artemis Swarm run: 55/57 PASS; both image-comparison failures subsequently
fixed and targeted reruns PASS (57/57 latest results across those runs).
See [current full-suite report](artemis-swarm-full-acceptance.md); earlier evidence below is historical.

## Revision, target and execution

- Application/deploy baseline: `4a80a0d3`, branch `codex/artemis-work-plane`.
- Fresh deployment source: `4c8923d2` (earlier manifest `fe5d4f4d`); application image tag unchanged.
- Source published to `http://192.168.88.50:3001/hiveforge/PocketHive.git` with
  explicit user permission. No GitHub push.
- All 19 application images published with confirmed digests through
  `tools/docker/remote-images.sh --skip-package --push`, tag
  `dev-20260921-g4a80a0d3`, registry `192.168.88.50:3001/hiveforge`.
  Existing staged Java artifacts were built and tested at parent `713e7559`;
  the deploy commit changes manifests, a public test TLS identity and documentation.
- HiveForge 0.5.9, environment `swarm`, executor `portainer-stack`.
- Project/deployment `pockethive-development`, component `stack`, profile `swarm-full`.
- Deployment ID: `deployment-080fc7b8-a6f0-496c-baa2-f3f890e62c84`.
- Successful deploy operation: `uiop-48b3b914-a373-4f97-afb9-c5dbb831ae1d`;
  action operation `op-3dca5d93-42e2-4a8e-957e-98f2218ae70d`.
- Public ingress: `https://192.168.88.50:8443`; MCP allowed Host includes `:8443`.
  Clients explicitly trust `deploy/hiveforge/runtime/dev-tls/server.crt`.
  The key and MCP/Auth values are intentionally public development fixtures.
- WORK: ARTEMIS. CONTROL: RabbitMQ. No automatic adapter switching.

## Infrastructure and manifests

Before deployment the user requested repair and full test-environment cleanup.
The .51 manager recovered after freeing full Docker/log filesystems and restarting
Docker, without a Raft reset. Old PH was removed through HiveForge; its data was
cleared and `docker system prune -af` ran on all four nodes with explicit approval.
Evidence is in `~/proxmox/DOCKER-SWARM-VM-REBUILD-TASK.md`.

HiveForge subsequently observed all four nodes READY and verified managed-root
bind visibility on every node. Deployment itself used HiveForge MCP only.

The Swarm template now explicitly selects WORK, provisions Artemis conditionally,
and passes broker settings through existing product configuration. Artemis has one
replica, stop-first updates/rollback and shared state at `/hf/state/artemis/data`,
owned by UID/GID1001. Existing bind mapping renders host-visible paths. This is not HA.
The shared update playbook received the four public/MCP settings previously only
bound by deploy. The existing MCP PID limit moved to Stack-compatible
`deploy.resources.limits.pids` without changing its value.

## Validation and review

Both Ansible playbooks pass syntax checking; action-root contract and diff checks
pass. All four combinations of swarm-full/swarm-reduced and ARTEMIS/RABBITMQ render
and pass `docker stack config`. Nginx configuration including Dev TLS passes `nginx -t`.

Review passes: plan matches explicit adapter deployment and ingress acceptance;
style retains existing Ansible/template ownership; conciseness adds no new runtime
parser or abstraction; security uses explicitly public test fixtures and real TLS
with client trust; libraries unchanged; maintainability records environment,
revision, validation and remaining limitations. Adapter topology/naming continues
through existing product owners, not deployment-side reconstruction.

## Acceptance evidence

All tests use the official HTTPS ingress and the independent acceptance framework.
Targets and a test truststore are in `/tmp/ph-swarm-acceptance-4a80a0d3`.
Persistent evidence is under `acceptance-tests/runs/`.

| Group | Result | Evidence directory |
| --- | --- | --- |
| smoke | PASS, 1 test | `platform-smoke-0d5c2fb0-17bf-4316-ab97-5be506d11714` |
| lifecycle: target state | PASS | `target-state-lifecycle-c5722401-51f1-41c6-b70e-7efc26e48068` |
| lifecycle: HTTP traffic | PASS | `http-lifecycle-045be168-3303-4f80-9632-c575c883de3d` |
| lifecycle: intentional failure cleanup | PASS | `failure-cleanup-61ed313e-c9da-4faf-bfed-0558d792c9e7` |
| delayed-delivery | PASS; 3000 ms configured, 3048/3116/3004 ms observed | `delayed-delivery-663628a4-4366-40a3-a40c-ce268293c7f2` |
| network-binding-recovery | PASS, invalid candidate rejected, previous binding and traffic retained, removal verified | `network-binding-recovery-8cdfe845-3936-4c30-a5e4-2d61abd31ca5` |

All six deployed tests passed. Final official-ingress `list-swarms` returned `[]`.
Logs: `/tmp/ph-swarm-{smoke,lifecycle,delayed,binding}.log`.

## Remaining issues and limits

- NW-4 now has cross-host proof (2026-09-22, below). Full remote replay of every
  acceptance group was not performed; historical local evidence remains scoped.
- No OAuth client interoperability or load-capacity claim is made.
- User-requested AGENTS.md update permits Dev/test repository pushes required by
  an authorized workflow; GitHub/other destinations require separate permission.

## MCP startup diagnosis and verified fix — 2026-09-21

With explicit user authorization, read-only host diagnostics showed Tomcat failing
to create `/tmp/tomcat.*` with `Read-only file system`. The actual service had only
the state bind, without either service-level tmpfs entry from the rendered manifest.

Explicit `volumes: type: tmpfs` reached Swarm, but the configured mode did not.
The first fix therefore exposed a second error: the root-owned spool mount denied
UID10001 creation of its private uploads directory. Final manifest `55e0986b`
uses one 128 MiB tmpfs at `/tmp`, preserving the previous aggregate budget, with
private `/tmp/pockethive-mcp-spool` and the existing 64 MiB upload quota. There is
no Java change, image rebuild, root user, or writable-root workaround.

Successful final HiveForge update: `uiop-9bd7fcc0-4b24-4bb7-b32d-bdbfb4922de5`,
action `op-6f89f118-f17f-4b92-bddc-cbf7ee26bec7`.
Verified on running task `9n3wgo4oieggetmviuant7edu`, mgr-3:

- health=healthy, readonly=true, restarts=0;
- `/tmp`: root-owned01777; private spool: UID/GID10001 and0700;
- actual mount flags: rw,nosuid,nodev,noexec,size=131072k;
- all18 HiveForge services at1/1;
- public HTTPS protected-resource metadata200, correct issuer/resource URLs;
- unauthenticated MCP initialize401, preserving authentication enforcement.

Local equivalent startup also passed. The six earlier ingress acceptance cases
remain the application regression evidence; metadata/authentication probes above
specifically verify this deployment-only repair, not a complete OAuth client flow.


### E2E rerun after MCP deployment repair — 2026-09-21

Against deployed manifest `55e0986b`, unchanged images `dev-20260921-g4a80a0d3`
and official HTTPS ingress .50:8443: **5 PASS / 1 FAIL**. Smoke and all three
lifecycle cases passed; binding recovery passed. Delayed delivery failed on one
2999 ms observation against the strict >=3000 ms assertion (other samples3046/3112).
The test compares generator processedAt and processor receivedAt from separate
processes; clock alignment/resolution remains a possible explanation, not a proven
cause. No assertion or runtime behavior was changed and no green rerun substituted.
The failed test completed REMOVE/SUCCEEDED with no remaining resources. Final
ingress list-swarms returned[]. Logs: `/tmp/ph-swarm-postfix-{smoke,lifecycle,delayed,binding}.log`.

Evidence under `acceptance-tests/runs/`:
- `target-state-lifecycle-675f2468-660a-46a5-a758-3c5073cac707`
- `http-lifecycle-36f37b85-1aff-4da9-be7d-768d57cc89c5`
- `failure-cleanup-b7e2f2e9-fcb4-4266-bde5-f368313a8cea`
- `delayed-delivery-b740d704-4341-4337-80df-de5fdaeeebc8`
- `network-binding-recovery-e1c750b1-7c39-4dfd-88f4-fb2017a6142d`

This run supersedes the earlier all-green result as the latest remote acceptance
status at that point. The user accepted the 2999 ms observation as functionally
valid; the strict timing assertion is corrected below. NW-4 still lacks cross-host
NPM/HAProxy evidence.

### Branch closeout verification — 2026-09-22

The delayed-arrival assertion now permits exactly 1 ms of timestamp measurement
error, as accepted by the user. The allowance is recorded with every observation;
broker scheduling and the delivery policy contract are unchanged. Fresh official
HTTPS execution passed: 3074/3135/3005 ms for configured 3000 ms, verified removal.
Evidence: `delayed-delivery-bb69d93a-181d-4029-a2cc-f315e58cefcd`.
Runner log: `/tmp/ph-swarm-closeout-20260922/delayed.log`; 178 acceptance-framework
unit tests also passed. This invocation ran one deployed test, not the historical
reports remaining in the Maven output directory.

HiveForge currently reports 18/18 running services and all four nodes ready.
NPM and HAProxy are still on mgr-2. The prepared optional placement settings in
[the deployment contract](../HIVEFORGE.md#optional-proxy-placement-for-cross-node-verification)
will pin HAProxy to wrk-1 and NPM to mgr-2. Both playbooks pass syntax checks;
eight profile/adapter/placement render combinations pass Stack validation and
five cases exercise the canonical placement assertion (empty pair, distinct pair,
each incomplete pair, identical hosts). Action-root contract check passes.
Publication and cross-host execution are pending; NW-4 remains PARTIAL.

### NW-4 cross-host execution completed — 2026-09-22

Manifest `fe5d4f4d` published to the Dev repository and deployed through HiveForge
update `uiop-be3819d5-e206-4e40-815f-ef253c44bec4` (action
`op-8e4b5efb-ed1c-42ba-a9ab-0581ee5d7510`). HAProxy moved to wrk-1/.53;
NPM remained mgr-2/.51. Both actual task IDs were stable before/after the test.

NW-4 passed, including rejected candidate (10019 ms), retained binding and real
traffic, explicit clear and verified removal. Final ingress registry `[]`, all18
services1/1. The canonical [coverage ledger](../ci/acceptance-coverage.md#nw-4--cross-node-swarmnfs-pass-2026-09-22)
records the run ID and evidence archive. No new application images were required.
Remaining: separate closing review, PR publication; N4 and A6 stay deferred.

Git authorization is now explicit in both local worktrees' AGENTS.md: the authorized
Dev deployment includes its scoped commits/pushes, without repeated requests.
The root PocketHive worktree change is left uncommitted with its unrelated work.
The earlier automatic-review rejection was resolved after the explicit user request:
HiveMind ruleset v7 now records the same persistent Dev workflow authorization.
