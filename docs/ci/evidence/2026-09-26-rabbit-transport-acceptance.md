# Full Rabbit acceptance after transport and catalogue changes — 26 September 2026

**65 cases passed; 0 failures, 0 errors, 0 skipped**, across 31 groups.

## Environment and scope

Current uncommitted `codex/f07-ph-ui` worktree based on `61887845`, rebuilt using
`build-hive.sh --quick`. Main public ingress: `http://localhost:8088/`.
CONTROL and WORK use RabbitMQ, with an explicit local Compose overlay selecting
`POCKETHIVE_WORK_TYPE=RABBITMQ`. The main stack remains running with this selection;
root Compose still defaults WORK to Artemis.

The full Rabbit-supported acceptance matrix ran sequentially, including START/STOP,
normal removal, TCP delayed response and timeout, WebAuth, Redis, authorization,
network recovery, exports and isolated fresh deployment. The Artemis-only broker
delayed-delivery group is excluded because Rabbit does not support that contract.
Local network recovery does not qualify cross-host Swarm/NFS behavior. This is not
a load test or browser acceptance run.

All deployed checks used public ingress. Maven retained the canonical acceptance
profile, group selection and Failsafe verification; repeated Surefire unit suites
were excluded. No acceptance assertions or product behavior changed for this run.
Prior clock-related failures remain historical evidence; a passing run does not
establish that host clock instability is resolved.

## Build repair

The first build reached the UI documentation stage but failed on two existing
coverage links whose evidence pages were absent from the Docusaurus include list.
Both existing pages were added to that list; the UI rebuild then passed. Backend
and worker images had already rebuilt successfully from the current source.

## Cleanup and evidence

The main swarm catalogue was empty after the suite. The temporary runner bundle
was deleted through Scenario Manager and its absence verified. Fresh deployment
used a separate Compose project, runtime directory and volumes at public ingress
port18088; its containers, network and volumes were removed after SM-2 passed.

Artifacts: `acceptance-tests/runs/local-rabbit-transport-catalogue-20260926/`.
This includes per-group targets/logs/JUnit results and operation evidence, source
HEAD/status/diff and untracked source snapshots, image identities, build/deploy
logs, fixture creation/removal evidence, final catalogue, and fresh-stack teardown.
No commit or push was performed.

| Group | Passed |
| --- | ---: |
| smoke | 1 |
| oauth-ingress | 8 |
| scenarios | 3 |
| auth-read | 13 |
| lifecycle | 3 |
| startup-failure | 1 |
| auth-viewer | 3 |
| auth-runner | 3 |
| auth-network | 3 |
| workers | 2 |
| templating | 2 |
| worker-config | 1 |
| worker-overrides | 1 |
| http-proxy | 1 |
| https-proxy | 1 |
| tcps-proxy | 1 |
| tcp-delayed | 1 |
| tcp-timeout | 1 |
| network-binding-recovery | 1 |
| scenario-plan | 1 |
| redis-fixture | 1 |
| redis-data | 1 |
| webauth-loop | 1 |
| tx-outcome | 1 |
| auth-provisioned | 3 |
| auth-scenario-mutations | 2 |
| auth-swarm-management | 1 |
| clearing-export | 1 |
| clearing-export-xml | 1 |
| clearing-export-streaming | 1 |
| fresh-deployment | 1 |
