# Lifecycle Control-Plane Rewrite — Release Verification Matrix

Status: historical release evidence
Scope: `0ba64e1e..47bfefe1` (`rewrite/lifecycle-control-plane`), 14 commits,
407 files changed, plus the current uncommitted E2E acceptance and release
evidence additions.

The custom E2E control-plane parser/audit recorded below was retired by the later
control-plane simplification work. Its results remain historical evidence for the
named revision, but it is not a current release gate. Current work is tracked in
`docs/todo/control-plane-simplification-plan.md`.

This is the acceptance record for the lifecycle/control-plane rewrite. It
separates code presence, automated proof, observed large-swarm behaviour, and
remaining release evidence. A green local test, a green E2E run, or a commit
alone must not be read as proof for a different column.

## Evidence rules

| Evidence | It proves | It does not prove |
|---|---|---|
| Commit | The reviewed source contains a change. | That an image was built, deployed, or executed. |
| Automated test | The named code path behaves under its controlled inputs. | Cross-node behaviour, deployed image identity, or live RabbitMQ/Docker state. |
| Large-swarm E2E | Behaviour through the documented ingress on the selected environment. | Which commit/image digest supplied the environment unless the deployment record is attached. |
| HiveForge deployment record | Exact source ref and image digest selected for deployment. | Functional convergence without its corresponding observation. |

## Recorded evidence snapshot

- Baseline: `86f57af7752051ffced6c580d63a965c06c5480c` (`main`, v0.15.35).
- Current branch head: `47bfefe1d316084515e9dc145d265a1d280fb7c0`.
- Superseded full environment run: `./start-e2e-tests.sh --target large-swarm`
  completed on 2026-08-03 with **38 tests, 0 failures, 0 errors, 3 skipped**
  in 1078.031 seconds.
- Current authoritative environment run: the same command, with no manual
  overrides and the saved `large-swarm.env`, completed on 2026-08-04 at
  13:41 BST. It passed **39 scenarios / 463 steps** in 19m13.169s; Surefire
  recorded **67 tests, 0 failures, 0 errors, 0 skipped** (Cucumber: 39 tests,
  0 failures, 0 errors, 0 skipped). This actively executes the previously
  excluded auth rollout and two history-policy scenarios. The mandatory FULL
  audit captured 3,889 control-plane messages and required all six families.
- Recorded targeted environment run: the unauthenticated-access scenario
  completed with 1 scenario and 3 steps passed while capture was active in
  `TARGETED` scope.
- HiveForge deployment receipt: `deployment-91766df3-a9fd-414d-9eb6-20e6de570574`
  was updated successfully by operation `op-04630958-5def-4bb6-a9eb-6c64c12248df`
  at 2026-08-03 16:44:50 UTC. Its recorded Compose artifact is
  `22242d60a030c1ff3e46d2a36df8ea19bcf21a41733034744cfc191e11223ec3`, and
  HiveForge verified that the current artifact has the same digest.
- That receipt selects the approved ref `rewrite-lifecycle-control-plane` and
  application image build `dev-20260803-1742-g4306c8b1b808`, i.e. source
  `4306c8b1b808da2e68114379f6b69486f6a55ed3`. The running services expose
  exact image digests, including Orchestrator
  `sha256:1759afcecf2c7f23ca2abb5414c0126e32918b4f00757cdaab17274167c078db`,
  Network Proxy Manager
  `sha256:67ce70be805a3fad3a25a0cc7d68593bc7fd28b8197f1dd8b34a4e056100503e`,
  HAProxy
  `sha256:f0c93b289dfeeefe628e67aeca2a84517c6cf2abc3647269672d8acf5fda8f17`,
  Scenario Manager
  `sha256:9ec628ab3e9a895fd4c430f60ee203bba14022f231c90f9e2ec1ab0146173324`,
  Auth Service
  `sha256:44ee582036bdb90cd4becea7c2167b9ff7c1ab234b712be29281d70f01a1c75e`,
  and TCP Mock
  `sha256:7ad431095a98e28501377664cd15d23c43655b17e49dd0f781cfa2799ddd54c6`.
- `e7cdef7f` and `47bfefe1` modify only E2E code and its runner, not a runtime
  image. Thus the recorded full E2E executes the current E2E gate against the
  deployed application source through `4306c8b1`; a new deployment is required
  only if a later runtime-image change is introduced.
- HAProxy and Network Proxy Manager are placed on different swarm nodes
  (`docker-swarm-wrk-1` and `docker-swarm-mgr-3` respectively). HiveForge
  records their shared NFS runtime mount at
  `/mnt/shared_nfs/hiveforge/data/deployed/pockethive-development/state/haproxy/runtime`,
  exposed as `/opt/haproxy-runtime` to both services; the deployed contract
  configures `haproxy.cfg` and `haproxy.applied.sha256` in that mount.
- Final post-REMOVE snapshot: the canonical PocketHive MCP inspected the final
  full-E2E swarm `pockethive-e2e-large-swarm-gp-5a7af85b235` after its
  successful REMOVE. The registry returned `[]`; the exact runtime view found
  zero workers, zero managers, zero queues, zero exchanges, and zero cleanup
  candidates. Its ownership manifest and swarm snapshot returned 404, which is
  the expected absence after the runtime directory teardown rather than an
  unremoved runtime artifact.
- Latest post-REMOVE registry check: after the 13:41 BST full run, the
  documented Orchestrator ingress returned `[]` for `list-swarms`.
- Final HiveMap evidence: `scan-pockethive-release-evidence-final-47bfefe1-20260804`
  covered 1,989 discovered sources (1,727 included, 262 explicitly excluded,
  no read failures) and compared `pass` with the 2026-08-03 branch baseline.
  It resolves the former HIGH full-audit-family finding; only the pre-existing
  MEDIUM large-coordinator maintainability risk remains. The complementary SSOT
  scan `scan-pockethive-release-evidence-ssot-final-47bfefe1-20260804` covered 150 current
  documentation sources, excluded 94 historical documents, found no conflicts,
  and compared `pass`.
- HiveForge confirms the `swarm-full` deployment is running and healthy. Its
  immutable Compose artifact binds RabbitMQ, Postgres, ClickHouse, and Redis to
  the four required `/opt/pockethive-data/*/data` roots on their labelled nodes,
  while HAProxy and Network Proxy Manager share the NFS HAProxy runtime mount.

## Matrix

| Workstream | Commits | Automated/local proof | Recorded large-swarm proof | Remaining release gate |
|---|---|---|---|---|
| Canonical lifecycle state, operation ownership, and public API | `0ba64e1e`, `922dc5b0`, `b9da7853`, `45b3f639` | Lifecycle schema/model, codec, routing, operation coordinator, Controller status, and REST tests; in particular `ControlPlaneCodecTest`, `ControlPlaneBoundaryArchitectureTest`, `SwarmOperationTest`, and `SwarmOperationCoordinatorTest`. | The 2026-08-04 full E2E passed create/start/stop/remove through ingress, bound to the recorded HiveForge application source through `4306c8b1`. | Rerun after any runtime-image change. |
| Auth, idempotency, conflicts, and terminal outcome publication | `e07c413c`, `9148c94b`, `e1449973` | `OperationOutcomePublisherTest`, operation-dispatch tests, REST/controller tests, and auth E2E scenarios cover 403-before-reservation, active-operation 409, outcome retry, and primary-error retention. | The 2026-08-04 13:41 BST full E2E passed unauthenticated 403, authorized lifecycle, idempotent target-state operations, and the terminal outcome path. The formerly excluded rollout scenario creates, starts, awaits the succeeded operation, and reads its authoritative controller target before scoped-admin checks. | Retain the operation/outcome evidence from the release run; do not treat an HTTP acceptance alone as completion. |
| Immutable startup artifact and filesystem REMOVE handshake | `0ba64e1e`, `40243ee5`, `cb7dc470`, `922dc5b0` | `FilesystemSwarmStartupArtifactStoreTest`, `FilesystemSwarmRemoveStoreTest`, and `RuntimeFilesystemLayoutTest` prove one validated path and digest contract. | Full E2E completed lifecycle cleanup; the recorded `swarm-full` Compose and healthy services confirm the four dedicated data-root bindings on their labelled nodes. | Rerun only after a filesystem/mount-contract change. |
| REMOVE postconditions and no orphaned resources | `03a36bda`, `e1449973` | `RuntimeRemovalPostconditionVerifierTest`, lifecycle controller tests, and removal-postcondition E2E assertions cover current observations, remaining-resource evidence, registry removal, and runtime-directory removal. | Full E2E completed REMOVE successfully; its final canonical MCP snapshot found no registry entry, worker/manager runtime objects, exact RabbitMQ queues/exchanges, or cleanup candidates. The missing ownership manifest is expected because the verified runtime directory no longer exists. | Rerun the snapshot after any lifecycle/remove change. |
| Network binding cleanup | `e1449973` | `RuntimeRemovalPostconditionVerifierTest` rejects using a non-Network-Proxy-Manager owner for this postcondition; Network Proxy Manager client tests cover the canonical boundary. | The 2026-08-04 full E2E passed HTTP, HTTPS, and TCPS proxy scenarios and asserted canonical binding absence after each REMOVE; the final post-REMOVE snapshot is clean. | Rerun after Network Proxy Manager or lifecycle/remove change. |
| HAProxy desired/applied digest protocol | `9148c94b`, `1c9f1b0d` | `HaproxyConfigClientTest` covers exact-digest acknowledgement and explicit timeout; local Docker reload testing was recorded during implementation. | The 2026-08-04 cross-node `@haproxy-nfs` E2E used the public Network Proxy Manager ingress: a valid binding returned only after the desired/applied handshake, an invalid HAProxy candidate failed after the explicit ~10 s acknowledgement timeout, the previously acknowledged binding remained active, and final clear succeeded. The deployed NPM and HAProxy are on distinct nodes and share the recorded NFS runtime mount. | None for the deployed application source through `4306c8b1`; rerun after a Network Proxy Manager/HAProxy runtime-image or mount-contract change. |
| Large-swarm target, cleanup, and environment ownership | `0ba64e1e`, `e1449973` plus resolved HiveMind findings `iss-4c362…`, `iss-5a110…`, `iss-2186…` | Target-profile and lifecycle contract tests are versioned; HomeLab cleanup was performed separately from this repository. | The saved `large-swarm` profile completed the full E2E pack. | Re-check the deployment profile and runtime cleanup plan immediately before the release run; no manual endpoint overrides. |
| History-policy data-plane observation | `4306c8b1`, `e7cdef7f` | Focused history-policy test passed; the expected policy is read from the returned template, roles are matched exactly, and work is observed by exchange tap. | The 2026-08-04 13:41 BST full E2E passed both formerly excluded history scenarios with exchange taps rather than a competing final-queue consumer; runtime statuses reported `FULL`, `LATEST_ONLY`, `DISABLED`, and `FULL` for generator, moderator, processor, and postprocessor respectively. | None beyond re-running after a scenario/template change. |
| Complete control-plane contract audit | `45b3f639`, `47bfefe1` | `ControlEventsContractAuditTest` rejects missing journal, alert, and metric; the full audit checks schema and routing/envelope identity for all six families. | The 2026-08-04 13:41 BST FULL E2E captured 3,889 messages and passed with `signal`, `result`, `outcome`, `journal`, `alert`, and `metric` required. The final HiveMap code-quality and SSOT scans both compare `pass`; the former HIGH audit-family gap is resolved. | Rerun the scans after the release-evidence files are committed or any contract/E2E change occurs. |
| Release identity and reproducibility | `1c9f1b0d`, `47bfefe1` | HiveForge templates require explicit image/tag inputs; the E2E runner has an explicit FULL/TARGETED audit scope. | HiveForge operation `op-04630958-5def-4bb6-a9eb-6c64c12248df`, Compose SHA-256 `22242d60…23ec3`, and live image digests record the deployed application source `4306c8b1…55ed3`; later committed changes and the current acceptance additions are E2E-only. | Preserve this receipt with the release run. A new receipt is required after any runtime-image change. |

## Previous findings resolved by this branch

- `iss-10e120…`: Swarm Controller status envelopes conflicted with the canonical
  control-event schema.
- `iss-2edf496…`: control-plane v2 consumers/DTOs did not enforce canonical
  version/runtime invariants.
- `iss-eb8fe…`: release gate omitted result/journal families and kept stale
  operation semantics.
- `iss-ff2fe…`: history-policy runtime E2E missed the final message because of
  a competing consumer and configuration drift.
- `iss-baad…`: a full control-plane audit could pass without every used message
  family.

Their resolved status records the code and test evidence, not a waiver of the
remaining release-identity, post-REMOVE topology, or final HiveMap gates above.

## Execution order for the remaining gates

1. Completed: execute the HAProxy/NFS cross-node acceptance scenario and retain
   its desired/applied handshake, rejection, timeout, and rollback evidence.
2. Completed: run `./start-e2e-tests.sh --target large-swarm` without overrides
   and retain the Surefire result together with the deployment receipt.
3. Completed: use PocketHive MCP to capture post-REMOVE registry, topology,
   queue, exchange, and operation-artifact absence evidence.
4. Completed for the current dirty worktree: run HiveMap and the repository-wide
   SSOT scan and retain their `pass` comparisons. Re-run those inexpensive
   scans after committing the release-evidence files, because their source
   revision must name the final merge head.
