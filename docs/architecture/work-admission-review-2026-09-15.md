# Rabbit / Artemis Work admission — separate review, 2026-09-15

Scope: uncommitted uniform-admission correction in `/home/sepa/PocketHive-artemis`,
branch `codex/artemis-work-plane`, HEAD `863694be777f8451ffc8a6dfb3d53e88c52e06b3`.
Also rechecked the earlier AR-REV-2 native subscription-state correction. This is
review of the current code paths, not acceptance of pending A3–A6 or unrelated
Orchestrator/Scenario Manager changes. No production/test source was changed here.

**Verdict: changes required.** Three reproduced regressions below. The new single
SDK admission owner is in place, and ordinary ACK/pause paths work, but passing
component tests do not cover the remaining delivery and lifecycle cases.

## WA-REV-1 — P1: Artemis cumulative ACK consumes an earlier unaccepted item

`ArtemisWorkInputChannel.deliver`, lines 107–113, leaves WorkNotAcceptedException
unsettled, then uses `message.acknowledge()` for subsequent deliveries. Core's
ordinary acknowledgement is cumulative for this consumer. A following malformed
envelope bypasses admission, reports decode failure and gets acknowledged, thereby
also acknowledging the earlier valid item that was never submitted.

This can occur between SDK admission pause and native consumer close, with buffered
deliveries. Reproduced on the real embedded broker in two ways:

- Direct port: reject a valid item, consume a malformed item, stop; pending count
  is **0**, expected **1**.
- Actual SDK, maxInFlight=1: one task remains executing, a second valid item waits
  for capacity, malformed JSON follows. Disable through the SDK state callback.
  A transport decorator schedules native close after the already-buffered malformed
  callback, exposing the legal pause/close interleaving. Only `accepted-0` executed;
  pending after stop is **0**, expected **1**. No broker failure is involved.

Logs: `/tmp/work-admission-review-artemis.log` and
`/tmp/work-admission-review-artemis-sdk.log`.
Required correction: settle only the intended delivery, using native individual
acknowledgement when earlier deliveries may remain unaccepted. Keep consume/report
for malformed input and no redelivery for accepted task failures.

## WA-REV-2 — P2: the lifecycle monitor prevents pausing a backlogged memory input

`MessageWorkInput.applyListenerState`, lines 137–146, now holds its monitor while
calling `channel.start()`. The supported InMemoryWorkChannel drains pending delivery
synchronously from start. With two pending messages and maxInFlight=1, the first task
runs while the start caller waits to admit the second. A concurrent stopListener
cannot enter applyListenerState, so it cannot call execution.pause to wake that waiter.

Reproduced using actual MessageWorkInput and InMemoryWorkTransport: after 1.2 seconds,
both start and stop remain blocked; the stop stack is at applyListenerState. Stop
returns only after the accepted task is released. For a stuck task this remains
blocked. This regresses the previous unsynchronized applyListenerState stopListener
path and violates the approved no-drain stop contract for the stateful test adapter.

Log: `/tmp/work-admission-review-memory.log`.
Required correction: allow admission pause independently of any monitor held by an
effectful transport start. Do not add another executor or admission policy to the fake.

## WA-REV-3 — P2: cached-pool expiry changes per-thread connection reuse

`MessageWorkExecutor`, lines 22–23, replaces the previous fixed pool whose core
threads explicitly did not time out with newCachedThreadPool. Idle executor threads
now expire after 60 seconds, discarding their ThreadLocal resources.

This affects an existing supported processor option, PER_THREAD. HttpProtocolHandler
selects a ThreadLocal client at lines 281–282. TcpProtocolHandler (lines 345–368) and
Iso8583ProtocolHandler retain every created per-thread transport until configuration
reload closes the old pool. Repeated idle periods therefore recreate clients and
retain obsolete TCP/ISO transport instances; changing this lifetime was not part of
the approved removal of inline execution.

Reproduced with the actual MessageWorkExecutor at maxInFlight=2 and a ThreadLocal
resource marker: after 63 seconds of idle time the original worker thread is dead,
the next task uses a new thread, and resource initialization count rises from 1 to 2.
Processor resource effects are established by the inspected ThreadLocal/created-pool
paths, not a live HTTP/TCP load test.

Log: `/tmp/work-admission-review-idle.log`.
Required correction: retain reusable worker threads while preserving the one bounded
asynchronous admission path at every limit. No processor refactor is required here.

## Ownership and call-path evidence

Canonical contract: `work-plane-boundaries.md`, Preserved Work delivery behavior,
human-approved correction of 2026-09-15; responsibility records below are in
`runtime-responsibilities.md`. Required headers exist and refer to those records.

| Responsibility and owner | Actual consumer path, effects and verdict |
| --- | --- |
| RESP-WORK-TRANSPORT: MessageWorkExecutor | MessageWorkExecution submits through one synchronized capacity gate. Pause wakes waiters; close shuts down without interrupting admitted tasks. No inline/rejection fallback remains. Unit tests support capacity/resize/close behavior; thread lifetime regresses PER_THREAD reuse (WA-REV-3). |
| RESP-WORK-TRANSPORT: MessageWorkInput | WorkerControlPlaneRuntime state callback → concurrency update → desired state → execution pause/resume → WorkInputChannel start/stop. Normal Rabbit/Artemis components pass; holding the lifecycle monitor through synchronous start blocks the memory path (WA-REV-2). |
| RESP-WORK-TRANSPORT / RESP-WORK-INVOCATION: MessageWorkExecution and DefaultWorkerRuntime | Delivery → executor → WorkMessageDispatcher → DefaultWorkerRuntime → WorkerInvocation → WorkOutputRegistry. Task exceptions are caught inside the executor and never reach broker settlement. No second result publication. WorkerInvocation's existing disabled-state guard and WorkInputLifecycle behavior are inherited, unchanged by this patch. |
| RESP-WORK-RABBIT-TRANSPORT / POLICY: RabbitWorkInputChannel and SpringRabbitListeners | Canonical body decoding → neutral handler → AUTO ACK; only not-submitted exception maps to ImmediateRequeueAmqpException. Actual Spring component tests verify ACK before worker completion and NACK for waiting work. No CONTROL policy change. No new finding in Rabbit settlement. |
| RESP-WORK-ARTEMIS-TRANSPORT: ArtemisWorkInputChannel | Core consumer → canonical codec → neutral handler → native settlement. Subscription state derives from native isClosed; broker/adapter close and failed restart tests pass, supporting AR-REV-2 correction. Ordinary SDK stop no longer waits for the executing worker, but settlement still violates preservation of unaccepted delivery (WA-REV-1). |
| RESP-WORK-TRANSPORT fixture: InMemoryWorkChannel | Resource lock reserves pending delivery; handler runs outside the lock; not-submitted delivery is restored without retry loop. Its six direct transport tests pass. Integration with SDK lifecycle exposes WA-REV-2; the transport is not a second owner of execution admission. |

Repository-wide Java search covered WorkDeliveryHandler implementations, onWork,
MessageWorkExecution, dispatcher.dispatch(workItem), SynchronousQueue,
newCachedThreadPool, the old inline maxInFlight branch and rejection fallback.
Inspected runtime dispatch, input lifecycle and processor ThreadLocal consumers in
addition to edited classes. No competing active admission owner found. Native imports
remain in adapter production; SDK Artemis/server dependencies are test-only. Existing
RepositoryImportBoundaryTest passed; no new scanner or ownership-selection test added.

## Six review passes

1. **Plan:** one asynchronous SDK admission path matches the approved correction;
   ordinary stop works, but unaccepted preservation and memory no-drain acceptance
   fail. Connection lifetime change is extra scope. A3–A6 remain explicitly deferred.
2. **Style:** focused production types and matching responsibility references;
   observed contract violations are the findings above, not missing-header issues.
3. **Conciseness:** one gate removes inline and rejection fallback branches; keep
   that simplification. Individual broker settlement and correct lock/pool policy
   can address the findings without another abstraction or adapter-local executor.
4. **Security:** no new trust boundary, credentials, selectors or resource-name
   construction in this correction. Component broker uses explicit isolated in-VM
   transport. Deployment authentication remains A3/A6, not verified here.
5. **Library:** JDK concurrency, existing Spring AMQP and managed Artemis versions
   suffice. Added SDK dependencies are test-only. Cached-pool lifetime is unsuitable
   for existing per-thread consumers (WA-REV-3); use standard pool facilities.
6. **Readability/maintainability:** admission and settlement ownership is visible;
   however a local settle boolean does not explain cumulative native ACK, and the
   start-under-monitor assumption excludes a supported transport. Address semantics,
   preserving the simpler single execution path rather than restoring inline mode.

## Verification and limits

Independent rerun: **705 tests, zero failures/errors/skips**, 21 reactor modules:
`./mvnw -B -ntp -pl common/worker-sdk,common/artemis-adapter -am test`.
Log: `/tmp/work-admission-review-reactor.log`. `git diff --check` passed.
The four targeted probes use the current compiled production classes and an isolated
temporary Java harness `/tmp/WorkAdmissionReviewProbe.java`; no production patch or
extra repository tests were introduced by review. All probes completed and cleaned up.

No stack/E2E or live Rabbit broker run. The real embedded Artemis probes and real
Spring listener/mocked AMQP tests prove only their exercised paths. Existing test
success does not waive WA-REV-1/2/3. AR-REV-2's native-state correction is supported;
the admission correction requires fixes before acceptance.


## WA-REV-1/2/3 implementation follow-up — 2026-09-15

Human instruction: fix the three findings. The following records implementation
and behavior evidence; it does not replace the separate review verdict above with
self-approval.

- **WA-REV-1:** ArtemisWorkInputChannel now calls individualAcknowledge for consumed
  messages. Not-submitted deliveries remain unsettled. Two embedded-broker cases
  reject one item, consume a later valid or malformed item, then verify the rejected
  item remains pending and is delivered once on restart. Both failed before the fix
  (pending=0 instead of 1) and pass after. The original actual-SDK pause/close probe
  also now leaves one pending item and executes only the already accepted task.
- **WA-REV-2:** MessageWorkInput records desired state under a short dedicated lock.
  Disable pauses admission before waiting for the transport monitor; enable applies
  only within serialized transport lifecycle processing. stop and close no longer
  acquire that monitor before pausing. Close is terminal, preventing late enablement
  from reopening a closed executor. The stateful memory adapter is unchanged in this
  follow-up. New tests cover stopListener, WorkInput.stop and close while start is
  admitting a backlog. Each timed out before the fix. After the fix, stop returns
  while the accepted task is still blocked, the next item remains pending, and
  restart delivers it exactly once (or close rejects restart).
- **WA-REV-3:** MessageWorkExecutor uses a standard ThreadPoolExecutor whose core and
  maximum sizes derive from its one admission limit. Core threads do not expire.
  A queue transfers already-admitted tasks to reusable threads, including the short
  completion/handoff gap; admission still bounds all submitted work. No inline path
  or rejection fallback exists. A behavior test warms both threads and their
  ThreadLocal resources, pauses for 61 seconds and resumes. Before: four resource
  initializations instead of two; after: the same thread/resource mapping remains.
  Existing live increase/decrease and accepted-task close tests also pass.

Before evidence: `/tmp/wa-rev-artemis-before.log` (two assertion failures) and
`/tmp/wa-rev-sdk-before.log` (three stop timeouts and one resource-reuse failure).
After evidence: **711 tests, zero failures/errors/skips**, 21 reactor modules:
`./mvnw -B -ntp -pl common/worker-sdk,common/artemis-adapter -am test`.
Log: `/tmp/wa-rev-fixed-reactor.log`.
Actual SDK probe: `/tmp/wa-rev-artemis-sdk-after.log` (pendingAfterStop=1, expected=1).
The existing import test and `git diff --check` pass. Repository-wide searches show
no old inline/cached-pool/rejection fallback path; admission remains in SDK,
individual settlement in Artemis, and result publication in DefaultWorkerRuntime.

Canonical responsibility records and headers were updated together. No wire,
CONTROL policy, processor implementation, manifest or A3–A6 changes. Native close
behavior outside the SDK admission path remains outside this correction. No stack,
E2E or live Rabbit run; Rabbit component checks use the real Spring listener and
mock AMQP client. WA-REV-1/2/3 are implemented with regressions, awaiting separate
follow-up review.


## Separate follow-up review after WA-REV fixes — 2026-09-15 (WA-REV-4 verdict withdrawn)

Scope: current uncommitted WA-REV-1/2/3 fixes on the same Artemis worktree and HEAD
863694be. Re-read current code, canonical contract/headers, factory/runtime consumers,
standard pool use and tests. No implementation or repository test changes in this
review. **The original WA-REV-4 blocking verdict is withdrawn by the reachability
audit below. The synthetic reproduction remains valid; it did not establish a
defect reachable through the current application flow.**

WA-REV-1 individual acknowledgement and WA-REV-3 retained core threads are supported
by source and independently rerun regressions. WA-REV-2's original blocked stop is
fixed for the tested isolated stop/close and subsequent restart. Its new ordering
still loses a required transport stop when requests overlap.

### WA-REV-4 — withdrawn P2; conditional observation from a synthetic overlap

MessageWorkInput.updateDesiredState pauses admission immediately (lines 135–142),
but applyListenerState later reads only the newest desiredEnabled (lines 147–163).
If enable arrives while disable is waiting for the transport monitor, both apply
calls can observe true. The gate reopens and a RUNNING channel skips both start and
stop. The already-applied pause is then forgotten without closing the old session.

Artemis deliberately leaves a not-submitted delivery unsettled until session close.
Consequently that item is stranded even though the input reports RUNNING and can
consume later messages. This concerns overlapping calls, not an ordinary sequential
stop that has completed before enable. No claim is made that default serialized CP
delivery triggers every such overlap; the affected SDK lifecycle API and bootstrap
callbacks are callable from different threads.

Deterministic component reproduction uses actual MessageWorkInput, its shared
executor and an embedded Artemis broker. A channel decorator holds the first start
return after the native consumer starts, making the lifecycle overlap reproducible:

1. At maxInFlight=1, `accepted` executes; `waiting` cannot yet be admitted.
2. stopListener pauses the gate, causing `waiting` to be rejected before submission,
   then waits for the in-progress start's lifecycle monitor.
3. A concurrent startListener records enable and also waits for that monitor.
4. Release the first start and join all callers. Complete the accepted task and
   publish `fresh`. Native state is RUNNING, starts=1, stops=0,
   executed=[accepted, fresh], pending=1. `waiting` has no active dispatch.
5. A second fully completed stop/start delivers `waiting`, confirming that it was
   stranded unsettled, not lost and not blocked by worker capacity.

Probe: `/tmp/WorkReenableReviewProbe.java`; result:
`/tmp/wa-followup-reenable-probe.log`. The harness only schedules existing calls;
it does not alter SDK state fields or native acknowledgement behavior. Recommended
repair: once pause has rejected admission, preserve the required transport stop as
an effect that must complete before reopening, even if desiredEnabled has changed.
Keep one SDK owner; do not add adapter-local admission or redelivery of accepted work.

### Ownership, review passes and evidence

| Responsibility | Evidence and outcome |
| --- | --- |
| RESP-WORK-TRANSPORT / MessageWorkInput | Factory supplies neutral channel; CP listener and lifecycle calls update this one owner. desired-state lock allows prompt pause while the transport monitor serializes calls, but its latest-state projection discards the already-required stop (WA-REV-4). Close remains terminal and the three original stop/close cases pass. |
| RESP-WORK-TRANSPORT / MessageWorkExecutor | One admission counter and limit; pool core/max sizes are derived dimensions, not an independent policy. No idle expiry of core threads or inline fallback. Live resize, accepted-task close and 61-second ThreadLocal reuse regressions pass. |
| RESP-WORK-ARTEMIS-TRANSPORT | Individual native ACK preserves earlier unaccepted deliveries for both subsequent valid and malformed input. Native consumer projection still handles broker/adapter loss and explicit failed restart. These tests pass; deferred settlement now exposes WA-REV-4 when the SDK omits close. |
| RESP-WORK-RABBIT-TRANSPORT / POLICY | Neutral not-submitted exception still maps to native requeue, normal callback return to AUTO ACK. Real Spring listener/mocked AMQP tests pass. No Rabbit-specific regression reproduced here. |
| RESP-WORK-INVOCATION | MessageWorkExecution → WorkMessageDispatcher → DefaultWorkerRuntime → WorkerInvocation → WorkOutputRegistry remains the sole execution/result path. Accepted failures remain diagnostic. Existing disabled-worker guard is inherited, not rewritten in this review. |

Repository-wide Java search inspected WorkDeliveryHandler implementations,
startListener/stopListener consumers, MessageWorkExecutor/setMaxInFlight, individual
and cumulative ACK calls, ThreadLocal consumers, old cached/SynchronousQueue/inline
fallback symbols. No competing admission or settlement owner was found. Test-only
Artemis SDK dependencies and existing import restrictions remain unchanged.

Six passes: (1) plan outcomes supported for the three original cases, overlap still
fails the preserved-delivery outcome; (2) focused types and required headers remain
aligned with intended responsibilities; (3) one gate/pool is concise, but latest-state
coalescing cannot discard a necessary transport effect; (4) no new trust/credential
boundary, deployment auth remains A3/A6; (5) standard JDK pool and managed adapter
libraries suffice, no new dependency; (6) the two locks explain serialization but
not preservation of an already-applied pause, which needs an explicit, readable fix.

Independent focused run: **30 tests, zero failures/errors/skips**, including the
existing import test and the real 61-second idle regression:
`./mvnw -B -ntp -pl common/worker-sdk,common/artemis-adapter -am test -Dtest=MessageWorkInputTest,MessageWorkExecutorTest,MessageWorkExecutionTest,InMemoryWorkAdmissionTest,ArtemisWorkInputChannelTest,ArtemisWorkAdmissionTest,RabbitWorkAdmissionTest,RepositoryImportBoundaryTest -Dsurefire.failIfNoSpecifiedTests=false`.
Log: `/tmp/wa-followup-review-tests.log`. Prior full selected reactor evidence is
711 passing tests; it was not rerun in full in this review. `git diff --check` passes.
No deployed/E2E/live Rabbit test. A3–A6 and unrelated service refactors remain deferred.


## WA-REV-4 reachability correction — 2026-09-15

The user challenged whether concurrent lifecycle calls actually occur. The earlier
review established a conditional SDK behavior, but incorrectly made it a blocker
without proving the required production callers. **WA-REV-4 is withdrawn as a P2
finding for the current application flow. No implementation change is required by
this observation.** This corrects the earlier recommendation, ownership table and
pass conclusions wherever they treated that synthetic overlap as a current defect.

Production call-path audit:

- `PocketHiveWorkerSdkAutoConfiguration.workerControlRabbitBinding` registers one
  worker CONTROL binding. Its synchronous path is `WorkerControlQueueListener.onControl`
  → `WorkerControlPlaneRuntime.handle` → `WorkerControlPlane.consume` →
  `ControlPlaneConsumer.consume` → `handleConfigUpdate` → `notifyStateListeners`
  → `MessageWorkInput.toggleListener`. There is no asynchronous handoff of these
  state callbacks. The next signal cannot execute before this callback returns
  when the CONTROL binding has one consumer.
- `SpringRabbitListeners.register(RabbitListenerBinding)` uses the Boot-configured
  Simple container. The resolved Spring Rabbit 3.2.10 bytecode initializes one
  concurrent consumer and no scaling maximum. Repository-wide searches found no
  CONTROL concurrency override. WORK's `concurrentConsumers` applies to a different
  subscription and does not introduce parallel CONTROL callbacks.
- `WorkInputLifecycle` starts inputs at phase 0. The Rabbit endpoint registry and
  containers have phase Integer.MAX_VALUE in the resolved dependency. Normal Spring
  lifecycle ordering starts the inputs before CONTROL reception and stops CONTROL
  before stopping the inputs. No custom phase/concurrent-startup override was found.
- `MessageWorkInputFactory` creates an input stored in `WorkInputRegistry`; it is
  not separately registered as a Spring bean or application listener. Merely
  implementing `ApplicationListener<ContextRefreshedEvent>` therefore does not
  supply the additional caller assumed by the earlier report. No production caller
  of the public `startListener` method was found; state changes use the callback.
- The probe instead used a mock control runtime, three manually created lifecycle
  threads and a decorator holding the first start return. It demonstrates what
  happens if those calls overlap, not that current worker entrypoints allow it.

The condition is not impossible under all configurations: Boot's
`spring.rabbitmq.listener.simple.concurrency` or `max-concurrency` can explicitly
introduce multiple CONTROL consumers, and future direct lifecycle callers could
also change the analysis. Neither is established in current repository wiring.
An externally modified deployment was not inspected. Revisit the conditional
observation if the supported CONTROL concurrency or caller model changes; it is
not a prerequisite for A3–A6.

Verification here is source/caller/configuration and resolved-library inspection,
plus `git diff --check`. No code, tests, configuration or dependencies changed; no
new test run is claimed. The previous 30-test review and 711-test selected reactor
evidence stand. Review passes for this correction: plan/conciseness/readability
now distinguish reachable behavior from artificial invocation; style, security
and library conclusions are unchanged because only review documentation changed.
No competing admission owner was introduced or found. Historical HiveMind blocked
checks are retained as historical evidence, with the finding corrected separately.
