package io.pockethive.worker.sdk.input.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.observability.ObservabilityContextUtil;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerInfo;
import io.pockethive.work.api.transport.*;
import io.pockethive.worker.sdk.config.MaxInFlightConfig;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.slf4j.LoggerFactory;

/** Drives real SDK admission through a supplied transport and observes task/receipt effects. */
final class WorkAdmissionScenario implements AutoCloseable {
    final CountDownLatch release = new CountDownLatch(1);
    final CountDownLatch started;
    final CountDownLatch failed;
    final AtomicInteger delivered = new AtomicInteger();
    final ConcurrentLinkedQueue<String> executed = new ConcurrentLinkedQueue<>();
    private final int limit;
    private final AtomicReference<Consumer<WorkerControlPlaneRuntime.WorkerStateSnapshot>> state = new AtomicReference<>();
    private final MessageWorkInput input;

    @SuppressWarnings({"unchecked", "rawtypes"})
    WorkAdmissionScenario(WorkInputChannel transport, int limit) {
        this.limit = limit;
        started = new CountDownLatch(limit);
        failed = new CountDownLatch(limit);
        var definition = mock(WorkerDefinition.class);
        when(definition.beanName()).thenReturn("worker");
        when(definition.role()).thenReturn("processor");
        var control = mock(WorkerControlPlaneRuntime.class);
        doAnswer(call -> { state.set(call.getArgument(1)); return null; })
            .when(control).registerStateListener(eq("worker"), any());
        var observed = new WorkInputChannel() {
            public void register(WorkDeliveryHandler handler) {
                transport.register(new WorkDeliveryHandler() {
                    public void onWork(WorkItem item) { delivered.incrementAndGet(); handler.onWork(item); }
                    public void onDecodeFailure(byte[] body, Exception failure) { handler.onDecodeFailure(body, failure); }
                });
            }
            public WorkInputChannelState state() { return transport.state(); }
            public void start() { transport.start(); }
            public void stop() { transport.stop(); }
        };
        input = MessageWorkInput.builder().logger(LoggerFactory.getLogger(WorkAdmissionScenario.class))
            .displayName("admission test").workerDefinition(definition).controlPlaneRuntime(control)
            .identity(new ControlPlaneIdentity("swarm", "processor", "worker"))
            .channel(observed).emitWorkErrorAlerts(false).dispatchErrorHandler(failure -> failed.countDown())
            .dispatcher(item -> {
                executed.add(item.messageId());
                if (item.messageId().startsWith("accepted-")) {
                    started.countDown();
                    if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Test work was not released");
                    throw new IllegalArgumentException("Expected failure after admission");
                }
                return null;
            }).build();
        input.initialiseStateListener();
        enable(true);
    }

    void enable(boolean value) {
        var snapshot = mock(WorkerControlPlaneRuntime.WorkerStateSnapshot.class);
        when(snapshot.enabled()).thenReturn(value);
        when(snapshot.config(MaxInFlightConfig.class)).thenReturn(Optional.of(() -> limit));
        state.get().accept(snapshot);
    }

    void pauseWhileCapacityIsFull() throws Exception {
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(delivered).hasValue(limit + 1));
        try (var caller = Executors.newVirtualThreadPerTaskExecutor()) {
            caller.submit(() -> enable(false)).get(3, TimeUnit.SECONDS);
        }
        assertThat(executed).hasSize(limit).doesNotContain("waiting");
        assertThat(failed.getCount()).isEqualTo(limit);
    }

    void finishAcceptedAndResume() throws Exception {
        release.countDown();
        assertThat(failed.await(2, TimeUnit.SECONDS)).isTrue();
        enable(true);
    }

    void assertExecutedExactlyOnce() {
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(executed).hasSize(limit + 1));
        assertThat(executed.stream().distinct().count()).isEqualTo(limit + 1);
    }

    static WorkItem item(String id) {
        var worker = new WorkerInfo("processor", "swarm", "worker", null, null);
        return WorkItem.text(worker, "payload").messageId(id)
            .observabilityContext(ObservabilityContextUtil.init(worker.role(), worker.instanceId(), worker.swarmId())).build();
    }

    @Override public void close() {
        release.countDown();
        input.close();
    }
}
