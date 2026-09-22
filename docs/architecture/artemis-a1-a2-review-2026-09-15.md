# Artemis A1/A2 — separate source review, 2026-09-15

Reviewed `codex/artemis-work-plane` at `863694be` plus the uncommitted Artemis
adapter, root build/import rules and plan/architecture changes. Worktree:
`/home/sepa/PocketHive-artemis`. No production code was changed during review.
Verdict: **changes required before accepting A1/A2** — two reproduced input
lifecycle defects. No competing Artemis naming/configuration owner was found.

## Findings

### AR-REV-1 — P1: stop closes the session before an active delivery can ACK

`common/artemis-adapter/src/main/java/io/pockethive/artemis/work/ArtemisWorkInputChannel.java:75–80`
closes the session while `deliver()` settles the current delivery only after the
callback returns (lines 98–105). Artemis 2.40.0 `ClientConsumerImpl` waits at most
the default 10,000 ms for the callback when closing; it then closes the consumer
and session even if the callback is still running.

Reproduction on the real embedded Core broker: publish one valid WorkItem, hold
its callback, call stop, release the callback after stop returns, then start again.
Stop returned after 10,022 ms; the original callback's ACK failed with
`ActiveMQObjectClosedException`, and **the same message reached the callback twice**.
This violates the approved no-redelivery behavior during an ordinary pause/resume.
It is relevant to the existing SDK: `MessageWorkExecution.onWork()` may perform
synchronous work, or wait for an executor slot; the input callback is not always short.

Correction must stop new delivery while retaining the ability to settle the
already accepted callback. Preserve the agreed callback-return ACK boundary;
do not make ACK depend on async executor success or merely increase a timeout.
Rabbit production behavior was not changed or reclassified by this review.

Evidence: `/tmp/ArtemisReviewProbe.java`, mode `slow-stop`, and
`/tmp/artemis-review-final-slow-stop.log`. The exact measured duration may vary;
the client's 10-second threshold and duplicate delivery are the relevant effects.

### AR-REV-2 — P2: a dead subscription remains RUNNING and start succeeds silently

`ArtemisWorkInputChannel.java:48–55` returns the last requested state and short-circuits
start on RUNNING. Only register/start/stop update that field; there is no observation
of session/consumer closure or failure. Reconnection is explicitly disabled by
`ArtemisSessions`, so a failed subscription will not revive underneath that state.

Reproduction: start and receive, stop the embedded broker, then inspect state and
call start. State remained RUNNING and start returned normally without establishing
a consumer. The existing consumer `MessageWorkInput.applyListenerState():128–137`
also skips start whenever desiredEnabled is true and the port reports RUNNING.
Thus its next enabled-state application cannot surface or restart the dead input.

The adapter must report actual subscription failure/closure and must not claim a
successful start against a dead session. This finding does not request automatic
reconnect, failover or a new retry policy. The SDK effect is source-traced;
Artemis service activation remains the explicit A3 follow-up.

Evidence: `/tmp/ArtemisReviewProbe.java`, mode `disconnect`, and
`/tmp/artemis-review-final-disconnect.log`.

## Responsibility evidence

Stable IDs below refer to `docs/architecture/runtime-responsibilities.md`; headers
were checked against their corresponding records and the actual effects.

| Contract | Owner, consumer path and effects | Alternative owners / verification / verdict |
|---|---|---|
| RESP-ARTEMIS-CONFIGURATION | `ArtemisConnectionSettings`, `ArtemisInputSettings`, `ArtemisOutputSettings` validate disjoint typed values; `ArtemisSettingValues` supplies common scalar rules. Factories consume those records. `ArtemisWorkIoType` and `ArtemisEnvironmentKeys` own selector and key literals. Constructors here are pure. | Repository-wide settings/key/client searches found no service-local counterpart. `ArtemisSettingsTest` passes. AUTHORING/Spring/ENV binding is explicitly future A3, not a claimed completed transfer. Supported for this slice. |
| RESP-ARTEMIS-CONNECTION | `ArtemisWorkPlane` composes neutral ports; `ArtemisSessions` owns locator/factory creation and session lifetime. Input/output use separate sessions; output creation occurs once through the existing `WorkOutputRegistryInitializer`, not on each config update or message. Facade close closes the native factory. | Only this module opens Artemis clients; no vendor types in the supported `api` signatures. Real broker tests and probe cleanup completed. Deployment TCP/auth and failure recovery remain unverified; input projection failure is AR-REV-2. |
| RESP-ARTEMIS-RESOURCE-NAMES | `WorkTopologyResolver` → `ArtemisWorkTopologyResolver` → `ArtemisResourceNames`; one formula supplies resource identities, transport addresses, ENV and status. `ArtemisResourceKind` owns native kinds. URI encoding/decoding stays here. | No alternative Artemis name builder outside the adapter. `ArtemisTopologyTest` covers separator/wildcard collisions, normalized aliases and exact resource URI round trips. Supported; service consumers are A3. |
| RESP-ARTEMIS-RESOURCES | `WorkPlaneResources` → `ArtemisWorkResources` applies/query/removes Core resources; `ArtemisManagement` performs bounded Core management deletion with verified reply. `appliedResources` is a partial-prepare receipt, not an ownership registry. Controller remove reverses resources, deleting each queue before its address; retainChannels preserves base addresses for failed prepare. Orchestrator's existing verifier owns final absence checks. | Repository search found no second Artemis resource implementation. `ArtemisWorkPlaneTest` verifies create/observe/remove, isolation, refusal to delete an occupied address and observation errors distinct from absence. No new manifest/cleanup path. Full startup/remove via Orchestrator remains A3/A4/A6; broker failure during partial prepare was source-reviewed, not fault-injection tested. |
| RESP-WORK-ARTEMIS-TRANSPORT | Factories → `ArtemisWorkInputChannel` → canonical `WorkItemJsonCodec` → `WorkDeliveryHandler`; output uses the same codec and one dedicated serialized producer session. No SDK dependency, worker execution, broker-header merging or second publish path. Input alone writes its subscription state. | Canonical codec reused, no duplicate parser. Existing real broker tests cover ordinary stop/start, malformed input, failing callbacks, async admission ACK and concurrent output. Review probes add AR-REV-1 and AR-REV-2; this responsibility is **not accepted**. A 1,000,000-character payload also delivered once, decoded successfully and left zero pending messages. |

Searches covered conventional Java production and tests, POMs and YAML/properties
across the entire worktree, including untracked module sources. Patterns included
`artemis`, `org.apache.activemq`, `createServerLocator`, `createAddress`,
`QueueConfiguration`, `artemis://`, Artemis ENV keys, `WorkItemJsonCodec`,
`URLEncoder`, `WorkInputChannelState` and actual `ensure`/`retainChannels`/remove
callers. The unrelated `OrchestratorControlQueueConfiguration` name match was
inspected as Rabbit CONTROL, not an Artemis owner. The existing import test checks
only its declared import restrictions; it is not evidence of behavioral compliance.

## Six required review passes

1. **Plan:** initial slice matches the accepted module/Core transport outcome;
   A3/A4/A5/A6 remain explicit. Manifest expansion and orphan cleanup are correctly
   excluded. A1/A2 acceptance is blocked by the two new adapter defects above.
2. **Style / boundaries:** 17 production types have separate files and named
   responsibility headers. No service/runtime mixing or second architecture scanner.
   Header subscription-state claims require AR-REV-2 to be corrected.
3. **Conciseness:** small types and existing ports/codec suffice; management helper
   uses the vendor protocol utility. No parallel scheduler, registry or cleanup.
   Lifecycle correctness must not be traded for the current short stop method.
4. **Security:** explicit endpoint/principal/password, redacted settings string,
   URI override rejection, encoded resource names and WORK-scoped removals checked.
   No auth/security manifest changes. Embedded broker has security disabled;
   deployment authorization, management permissions and TCP security are unverified.
5. **Library:** official Artemis Core client is necessary for the new broker;
   server is test-scoped, version follows the existing Spring Boot BOM. Standard
   JDK encoding/digest and vendor management helpers avoid a custom protocol.
   No independent dependency upgrade or vulnerability audit is claimed.
6. **Readability / maintainability:** naming, config, resource effects and codecs
   have clear owners. Hidden native close timing and stale state are the concrete
   failure-behavior gaps. Fix those within the adapter without spreading policy
   into SDK/services; this also preserves the conciseness result.

## Verification and limits

Fresh review run:
`./mvnw -B -ntp -pl common/artemis-adapter,common/control-plane-core -am test`.
**309 tests, 0 failures/errors/skips**, 10 reactor modules; includes 23 Artemis,
79 Rabbit and 69 control-plane-core tests (including the existing import test).
Log: `/tmp/artemis-review-reactor.log`. `git diff --check` passed.

Independent probes compile against that run's component-test classpath, use the
same real embedded in-VM Core broker and close adapter/broker before exiting.
They are temporary review reproductions, not production edits or added repo tests.
Initial standalone probe processes lingered after results; the final harness
explicitly exits after resource cleanup, as Surefire does. This is not counted as
an adapter shutdown finding. Final logs are prefixed `artemis-review-final-`.

No PocketHive stack/deployment, direct backend HTTP diagnostics or E2E were run.
Current app bootstrap still does not select Artemis: accepted A3/A4 work, not an
additional finding. Delayed delivery and actual 3DS timing contract remain A5.
Do not accept the transport slice until AR-REV-1/2 have behavior regressions and
a separate follow-up review. No production fixes, commits or pushes in this review.

## AR-REV-2 implementation follow-up — 2026-09-15

Human instruction: fix AR-REV-2 now; leave AR-REV-1 for discussion. This section
records implementation evidence, not a new review acceptance.

ArtemisWorkInputChannel now derives NOT_REGISTERED/STOPPED/RUNNING from handler
registration and the current native consumer's closed state. The copied RUNNING
flag was removed. Explicit start cleans up a dead subscription before opening a
session; failure stays explicit. Stop also disposes a retained dead session.
No failure-listener registry, automatic reconnect, new port state or SDK change.
The delivery/ACK method is byte-for-byte unchanged; the active-callback close
policy and AR-REV-1 remain unresolved.

New ArtemisWorkInputChannelTest reproduces broker loss and adapter closure.
Both tests failed against the pre-fix code (RUNNING instead of STOPPED), then
passed with the correction, including rejection of false-successful restart.
Logs: `/tmp/artemis-ar-rev2-before-tests.log` and
`/tmp/artemis-ar-rev2-final-tests.log`.

The first broader run passed both regressions but exposed an existing brittle
roundtrip assertion: identical step-header values serialized in different JSON
member order. ArtemisWorkPlaneTest now compares complete parsed JSON values via
Jackson instead of byte ordering; no codec/transport change or fields excluded.
The final selected reactor passed **311 tests, zero failures/errors/skips**,
including 25 Artemis tests, 79 Rabbit and the repository import test. Command:
`./mvnw -B -ntp -pl common/artemis-adapter,common/control-plane-core -am test`.
`git diff --check` passed. No stack/E2E, commit or push.

AR-REV-1 remains an open blocker. AR-REV-2 is implemented with regression evidence,
awaiting separate follow-up review; A1/A2 as a whole is not accepted yet.


## Uniform admission implementation follow-up — 2026-09-15

After discussion, the human explicitly approved removal of SDK inline execution for
Rabbit and Artemis. This supersedes the earlier instruction to leave AR-REV-1 open
for discussion, but does not turn this implementation handoff into review acceptance.
The canonical behavior is in work-plane-boundaries.md, Preserved Work delivery behavior.

MessageWorkExecutor owns bounded admission and executor lifetime for every limit.
MessageWorkExecution submits and reports task failures; it no longer selects inline
execution or falls back after rejection. MessageWorkInput pauses admission before
channel stop, waking capacity waiters without waiting for accepted tasks. A neutral
WorkNotAcceptedException means no task was submitted. Rabbit translates that outcome
to native requeue; Artemis leaves it unsettled until session close. Accepted failures
cannot escape the executor into either transport callback. CONTROL and wire envelopes
are unchanged. The stateful test adapter preserves rejected items without a retry loop.

Evidence: **704 tests, zero failures/errors/skips, 21 reactor modules** with
`./mvnw -B -ntp -pl common/worker-sdk,common/artemis-adapter -am test`.
Log: `/tmp/work-admission-reactor-tests.log`.
ArtemisWorkAdmissionTest uses the actual SDK with an embedded Core broker;
RabbitWorkAdmissionTest uses the actual SDK and Spring listener with a mocked AMQP
client. Both cover maxInFlight 1 and 2, ACK without worker completion, full-capacity
pause without draining accepted tasks, then restart and delivery only of the waiting
item. Accepted tasks deliberately fail after ACK. MessageWorkExecutorTest also
checks live limit increase/decrease and close without interrupting accepted work.
The existing repository import test passed. After adding the pending-delivery regression,
all 6 InMemoryWorkTransportTest cases passed with zero failures/errors/skips:
`./mvnw -B -ntp -pl common/work-test-fixtures -am test -Dtest=InMemoryWorkTransportTest -Dsurefire.failIfNoSpecifiedTests=false`.
Log: `/tmp/work-admission-fixture-tests.log`.

Ownership search covered all repository Java sources for MessageWorkExecution,
WorkDeliveryHandler implementations, dispatcher.dispatch(workItem), SynchronousQueue,
inline maxInFlight branches and the old rejection fallback. The Work admission gate
is only MessageWorkExecutor; SDK owns task failure reporting, each adapter owns native
settlement, and DefaultWorkerRuntime retains the existing sole result-publication path.
No extra import scanner was added. SDK Artemis/native broker dependencies are test-only.

Limits: the AR-REV-1 correction covers the SDK execution/admission path. It does not
change native Core close timeout for an arbitrary external blocking callback, nor
guarantee settlement during a lost broker connection. Rabbit evidence is component
level, not a live Rabbit broker or stack/E2E run. A3–A6 remain pending. AR-REV-1 and
AR-REV-2 fixes require separate review before A1/A2 acceptance.
