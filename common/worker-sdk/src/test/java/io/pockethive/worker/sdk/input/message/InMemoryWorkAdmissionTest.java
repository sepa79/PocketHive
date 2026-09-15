package io.pockethive.worker.sdk.input.message;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.testing.InMemoryWorkTransport;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

class InMemoryWorkAdmissionTest {
    @ParameterizedTest
    @ValueSource(strings = {"listener", "lifecycle", "close"})
    void stopWakesBackloggedStartWithoutWaitingForAcceptedWork(String operation) throws Exception {
        var transport = new InMemoryWorkTransport();
        String address = "memory://admission/jobs";
        transport.create(address);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var executed = new ConcurrentLinkedQueue<String>();
        var definition = mock(WorkerDefinition.class);
        when(definition.beanName()).thenReturn("worker");
        when(definition.role()).thenReturn("processor");
        try (var input = MessageWorkInput.builder().logger(LoggerFactory.getLogger(getClass()))
                .displayName("admission test").workerDefinition(definition)
                .controlPlaneRuntime(mock(WorkerControlPlaneRuntime.class))
                .identity(new ControlPlaneIdentity("swarm", "processor", "worker"))
                .channel(transport.input(address)).emitWorkErrorAlerts(false)
                .dispatcher(item -> {
                    executed.add(item.messageId());
                    if (item.messageId().equals("accepted")) {
                        entered.countDown();
                        try { release.await(); } finally { finished.countDown(); }
                    }
                    return null;
                }).build();
             var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            input.start();
            var waiting = WorkAdmissionScenario.item("waiting");
            transport.output(address).publish(WorkAdmissionScenario.item("accepted"));
            transport.output(address).publish(waiting);
            var start = callers.submit(input::startListener);
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                var stop = callers.submit(() -> {
                    switch (operation) {
                        case "listener" -> input.stopListener();
                        case "lifecycle" -> input.stop();
                        case "close" -> input.close();
                        default -> throw new AssertionError(operation);
                    }
                });
                stop.get(2, TimeUnit.SECONDS);
                start.get(2, TimeUnit.SECONDS);
                assertThat(finished.getCount()).isEqualTo(1);
                assertThat(executed).containsExactly("accepted");
                assertThat(transport.pending(address)).containsExactly(waiting);
                if (operation.equals("close")) {
                    assertThatThrownBy(input::startListener).isInstanceOf(IllegalStateException.class);
                }
            } finally { release.countDown(); }
            assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
            if (!operation.equals("close")) {
                input.startListener();
                await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    assertThat(executed).containsExactly("accepted", "waiting"));
                assertThat(transport.pending(address)).isEmpty();
            }
        }
    }
}
