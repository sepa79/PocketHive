# Full local acceptance — 26 September 2026

Result: **66 cases: 63 PASS, 3 ERROR, 0 assertion failures, 0 skipped**.
This is not a green full-suite result. The three errors share the TCP mock API
authentication mismatch described below. No product code, assertions or authentication
configuration was changed to obtain the result.

## Deployment and scope

- Source: `61887845`, branch `codex/f07-ph-ui`.
- Rebuilt all local application/worker images using `./build-hive.sh --quick` after
  source/component verification. Main public ingress: `http://localhost:8088/`.
- WORK: Artemis; CONTROL: RabbitMQ. The Rabbit WORK variant was not run.
- Ran all 31 explicitly mapped main-suite groups through `run-acceptance-tests.sh`,
  then fresh-deployment against a new isolated Compose project on port 18088.
- Includes OAuth ingress redirect checks, lifecycle/target-state/startup failure,
  worker history/config/overrides, templating, proxy HTTP/HTTPS/TCPS, binding recovery,
  scenario plan, Redis, WebAuth, ClickHouse, authorization, delayed delivery and exports.
- NW-4 was run on local Compose. It does not prove cross-host Swarm/NFS behavior.
- These are API acceptance tests, not manual/browser UI or load qualification.
- The framework's 179 component tests passed during canonical runner invocations;
  repeated component executions are not counted as deployed acceptance cases.

## Three errors with one cause

| Group | Failing prerequisite | Result |
| --- | --- | --- |
| tcp-delayed | GET `/tcp-mock/api/mappings` | HTTP 401, expected 200 |
| tcp-timeout | GET `/tcp-mock/api/mappings` | HTTP 401, expected 200 |
| webauth-loop | GET `/tcp-mock/api/requests` | HTTP 401, expected 200 |

`acceptance-tests/.../api/TcpMockApi.java` still unconditionally uses Basic Auth
with the target's `mockUsername`/`mockPassword`. Current Compose explicitly selects
`TCP_MOCK_AUTH_PROVIDER: POCKETHIVE`; `TcpMockAuthFilter` requires a Bearer token.
The cases fail at the initial mapping/journal read, before exercising their intended
traffic assertions. Successful TCPS traffic does not close these missing assertions.
Next repair is the test client's explicit authentication contract for the selected
mock provider, followed by rerunning these three cases. Do not change deployment
security or add a fallback to make tests green.

## Cleanup and fresh installation

After the main suite, the supported diagnostic CLI queried the main public ingress:
`list-swarms` returned `[]`. Every executed test retained its canonical cleanup checks.
The main local stack remains running.

SM-2 used a new project `ph-sm2-2a70da77-4087-4d77-806a-9f2a4af8c2a2`, a new runtime
directory and new named volumes, using the documented fresh-local Compose overlay.
Public ingress was `http://localhost:18088/`; its public-origin setting matched that
port. Fresh-deployment passed. Its containers, network and named volumes were then
removed; creation and teardown logs are retained. Main-stack state was not reset.

## Evidence

Ignored local artifacts: `acceptance-tests/runs/local-full-61887845-20260926/`.
`summary.json` indexes all 32 groups; numbered directories retain exact target files,
runner logs, per-group JUnit XML and operation/capture evidence. Build output and
`final-swarms.json` are also retained. The fresh-deployment directory includes creation
and teardown evidence. These artifacts must be retained when preparing the PR.

## Group results

| Group | Cases | Passed | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| smoke | 1 | 1 | 0 | 0 |
| oauth-ingress | 8 | 8 | 0 | 0 |
| scenarios | 3 | 3 | 0 | 0 |
| auth-read | 13 | 13 | 0 | 0 |
| lifecycle | 3 | 3 | 0 | 0 |
| startup-failure | 1 | 1 | 0 | 0 |
| auth-viewer | 3 | 3 | 0 | 0 |
| auth-runner | 3 | 3 | 0 | 0 |
| auth-network | 3 | 3 | 0 | 0 |
| workers | 2 | 2 | 0 | 0 |
| templating | 2 | 2 | 0 | 0 |
| worker-config | 1 | 1 | 0 | 0 |
| worker-overrides | 1 | 1 | 0 | 0 |
| http-proxy | 1 | 1 | 0 | 0 |
| https-proxy | 1 | 1 | 0 | 0 |
| tcps-proxy | 1 | 1 | 0 | 0 |
| tcp-delayed | 1 | 0 | 1 | 0 |
| tcp-timeout | 1 | 0 | 1 | 0 |
| network-binding-recovery | 1 | 1 | 0 | 0 |
| scenario-plan | 1 | 1 | 0 | 0 |
| redis-fixture | 1 | 1 | 0 | 0 |
| redis-data | 1 | 1 | 0 | 0 |
| webauth-loop | 1 | 0 | 1 | 0 |
| tx-outcome | 1 | 1 | 0 | 0 |
| auth-provisioned | 3 | 3 | 0 | 0 |
| auth-scenario-mutations | 2 | 2 | 0 | 0 |
| auth-swarm-management | 1 | 1 | 0 | 0 |
| delayed-delivery | 1 | 1 | 0 | 0 |
| clearing-export | 1 | 1 | 0 | 0 |
| clearing-export-xml | 1 | 1 | 0 | 0 |
| clearing-export-streaming | 1 | 1 | 0 | 0 |
| fresh-deployment | 1 | 1 | 0 | 0 |
