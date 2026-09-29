# Full local Rabbit acceptance — 26 September 2026

Initial complete run: **65 cases: 61 PASS, 1 FAIL, 3 ERROR, 0 skipped**.
One additional targeted START/STOP rerun passed on unchanged code. It does not
invalidate the original timeout or make this a green full-suite result.

## Setup

- Same application/worker images built from `61887845` as the preceding Artemis run.
- Main public ingress `http://localhost:8088/`; CONTROL and WORK both RabbitMQ.
- Explicit local Compose overlay sets Orchestrator `POCKETHIVE_WORK_TYPE=RABBITMQ`.
  Orchestrator was recreated only after confirming an empty swarm registry. No source
  default or security settings changed. The running main stack is left on Rabbit;
  root Compose still defaults to Artemis, so a later ordinary rebuild will restore it.
- Selected Rabbit fixture/target variants for 30 main groups, then an isolated fresh
  Rabbit deployment. Delayed-delivery was excluded as unsupported by this Rabbit adapter;
  its preceding Artemis result remains separate evidence, not Rabbit coverage.
- Startup-failure deliberately selected the incompatible Artemis fixture.
- Scoped runner needed a temporary `demo/acceptance-runner-rabbit` bundle copied from
  the explicit Rabbit HTTP fixture with a distinct ID/name. Its content is archived;
  after testing, deletion through Scenario Manager returned204 and readback404.
- Canonical acceptance Maven invocation retained Failsafe profile, groups, target and
  verification. After smoke/OAuth, `-Dtest=!**/* -Dsurefire.failIfNoSpecifiedTests=false`
  excluded only repeated Surefire unit tests. Actual Failsafe case counts were archived
  per group. No deployed test was skipped or had its assertions changed.

## New failure: STOP outcome timeout

`TargetStateLifecycleAcceptanceIT.acceptsStopBeforeStartAndRepeatedRequestsAtTargetState`
failed on the final STOP after the START/repeated-START sequence. STOP-before-START and
repeated STOP had already succeeded. Operation `98903b2f-d781-46be-80dd-d91b7f456669`
for swarm `acceptance-8b118bf9-45e6-4b80-9bed-86e13172e351` exceeded its90-second budget.
Cleanup initially refused to proceed while that STOP was active.

The canonical operation eventually read `TIMED_OUT`, with requested and observed
workload state both `STOPPED` and `nonConvergedWorkers: []`. These projections do not
prove successful completion of that specific command. Controller logs, JVM thread dump,
public snapshot/state and operation results are archived in `stop-diagnostics/`.
The subsequent diagnosis below establishes wall-clock ordering rejection; no deadlock
or broker-specific cause is claimed.

After timeout, normal public REMOVE `084752cf-f7b7-4c03-8a07-ea90c02e696b` succeeded,
with `remainingResources: []` and `errors: []`. No reset, restart or direct resource
removal was needed. A separate target-state rerun passed on unchanged images.
Treat this as an intermittent unresolved lifecycle-result defect requiring diagnosis,
not a fixed issue or a reason to relax the assertion. The preceding Artemis run passed
this case once; that does not establish the defect is exclusive to Rabbit.

## STOP diagnosis — wall-clock ordering rejection

The Orchestrator log records an incoming `event.result.swarm-stop` for this controller,
then `IllegalArgumentException: completedAt must not precede createdAt` at
`SwarmOperation:39 -> complete:76 -> SwarmOperationCoordinator.recordResult:89 ->
SwarmOperationTerminalHandler.accept:89`. The listener catches it and drops the event.
Hive journal event604 independently records the same dropped result/error.

Operation creation was `16:15:25.609Z`; the result timestamp was
`16:15:25.346421737Z`, about263ms earlier. The Orchestrator's own receive/error log
and journal timestamps also move backwards relative to dispatch (event600).
This demonstrates non-monotonic wall-clock evidence in this run; the OS/VM/NTP
mechanism that caused it was not investigated. This is not evidence of Rabbit
transport loss. Captured snippets truncate the full result payload, so its exact
terminal status is not claimed from these logs.

The execution path passes the executor's wall-clock timestamp into the operation's
`completedAt`. The record constructor rejects an earlier instant, leaving the owner
in DISPATCHED; later expiry creates TIMED_OUT. A standalone reproduction using the
current compiled coordinator/model classes and these two timestamps confirms that
sequence deterministically, including for a SUCCEEDED result. No product files or
acceptance assertions were modified, and no extra deployed run was necessary.

### Host-clock follow-up

User explicitly selected environment diagnosis rather than changing PH time semantics.
Read-only host diagnostics found Docker running inside local WSL2 (Marax), kernel
`6.18.33.2-microsoft-standard-WSL2`, clocksource `hyperv_clocksource_tsc_page`.
Host syslog records `Clock change detected` at `17:15:25.254340+01:00`, exactly during
the failed STOP, and then roughly every31seconds. `systemd-timesyncd` polls
`ntp.ubuntu.com` every32seconds; its observed offset was approximately-1.08seconds.
Live measurement captured wall-clock steps of-1.100229s and-1.097563s.

A separate paired live measurement over16Windows samples measured15.119seconds of
Windows UTC against15.644438seconds of WSL monotonic time (approximately3.5% faster).
WSL wall time advanced14.548743seconds over that interval, including a correction.
This identifies abnormal local WSL clock progression and repeated synchronization
corrections as the environment problem to address first. The deeper Hyper-V/kernel
cause remains unconfirmed. Windows W32Time was Stopped/Manual; that is an additional
configuration finding, not proof it caused the WSL rate error.

No clock service, host setting, or PH code was changed. The preceding application
reproduction remains valid evidence of how the bad host clock surfaced as STOP
TIMED_OUT, but changing PH timestamp semantics is not the selected repair. Stabilize
the environment clock and rerun the lifecycle check first.

Evidence: `stop-diagnostics/orchestrator.log`, `hive-journal-stop.json`,
`StopClockRepro.java`, and `clock-reproduction.txt`. Public paged journal access with
a removed swarm filter returns404; the deployment journal remains available and
provided the archived entries.

## Existing three errors

TCP delayed response and TCP timeout fail at GET `/tcp-mock/api/mappings`401.
WebAuth fails at GET `/tcp-mock/api/requests`401. These are the same prerequisite
failures as on Artemis: TcpMockApi sends Basic Auth while the deployment selects
PocketHive Bearer authentication. Their intended traffic assertions were not reached.
The test client needs explicit support for the selected authentication mode.

## Completed scope and cleanup

All other cases passed, including actual Rabbit worker traffic, history/config and
overrides, HTTP/HTTPS/TCPS proxies, local binding rollback/recovery, the complete
scenario timeline, Redis data, ClickHouse, authorization and all three exports.

Fresh deployment used new project `ph-sm2-4a799bf5-82db-47dc-932d-6d82a31c40d1`,
new runtime directory and named volumes, public ingress port18088 and Rabbit WORK.
SM-2 passed and its containers/network/volumes were removed. Main registry is `[]`
after the extra lifecycle rerun; main stack remains running. Temporary runner fixture
is removed. Local NW-4 is not cross-host Swarm/NFS qualification. No load/browser
acceptance, product repair, commit or push was performed.

## Evidence

`acceptance-tests/runs/local-rabbit-full-61887845-20260926/` contains summary.json,
numbered group logs/targets/JUnit/operation evidence, explicit Compose overlay,
fixture snapshot, STOP diagnostics, final registry and fresh-install creation/teardown.
The extra rerun is stored separately in `32-target-state-rerun/`; it is not counted
as a new distinct acceptance case. Prior Artemis results are in
[the Artemis report](2026-09-26-f08-local-acceptance.md).

| Group | Cases | Passed | Failures | Errors |
| --- | ---: | ---: | ---: | ---: |
| smoke | 1 | 1 | 0 | 0 |
| oauth-ingress | 8 | 8 | 0 | 0 |
| scenarios | 3 | 3 | 0 | 0 |
| auth-read | 13 | 13 | 0 | 0 |
| lifecycle | 3 | 2 | 1 | 0 |
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
| tcp-delayed | 1 | 0 | 0 | 1 |
| tcp-timeout | 1 | 0 | 0 | 1 |
| network-binding-recovery | 1 | 1 | 0 | 0 |
| scenario-plan | 1 | 1 | 0 | 0 |
| redis-fixture | 1 | 1 | 0 | 0 |
| redis-data | 1 | 1 | 0 | 0 |
| webauth-loop | 1 | 0 | 0 | 1 |
| tx-outcome | 1 | 1 | 0 | 0 |
| auth-provisioned | 3 | 3 | 0 | 0 |
| auth-scenario-mutations | 2 | 2 | 0 | 0 |
| auth-swarm-management | 1 | 1 | 0 | 0 |
| clearing-export | 1 | 1 | 0 | 0 |
| clearing-export-xml | 1 | 1 | 0 | 0 |
| clearing-export-streaming | 1 | 1 | 0 | 0 |
| fresh-deployment | 1 | 1 | 0 | 0 |

## TCP mock authentication repair and focused verification

TcpMockApi now uses the existing PocketHive Bearer token supplied by LiveRun/ApiRun.
The six TCP/WebAuth Rabbit/Artemis targets no longer carry unused Basic credentials;
TargetLoader rejects obsolete settings. No mock auth policy, deployment configuration,
product transport or time semantics changed. PocketHiveHttp remains the HTTP owner;
there is no authentication fallback, extra login or retry after401.

Verification:26 framework tests passed (TcpMockApiTest3, TargetLoaderTest23), including
wire-header assertions on both read paths and propagation of401 without a retry.
The focused deployed Rabbit run reached all three actual behavioral checks:
TCP timeout PASS; five-customer WebAuth loop PASS; TCP delayed response initially
failed its duration assertion (3916ms versus5000ms), despite a successful exact response.
Host syslog shows a clock correction at18:47:09.087600+01:00 during that exchange.
One unchanged delayed-response rerun PASS. Both runs remain archived, so this does
not rewrite the initial full suite or hide the local clock instability.
Final public swarm registry:[]; no commit/push performed.

Evidence directories:
- `acceptance-tests/runs/local-rabbit-tcp-auth-fix-20260926/`
- `acceptance-tests/runs/local-rabbit-tcp-delayed-confirm-20260926/`

Review: scope is the client auth mismatch; one session/HTTP owner retained; obsolete
credentials removed; existing token and same-ingress restriction reused; no new libraries
or public product contracts; target/docs and constructor call sites checked. No competing
TCP mock authentication owner remains in the active acceptance client. Generic Basic
HTTP support remains for other explicitly configured observers such as Grafana.
