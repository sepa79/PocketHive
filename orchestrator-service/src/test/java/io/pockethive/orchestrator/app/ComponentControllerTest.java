package io.pockethive.orchestrator.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ConfirmationScope;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.spring.ControlPlaneProperties;
import io.pockethive.orchestrator.infra.InMemoryIdempotencyStore;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class ComponentControllerTest {

    @Mock
    AmqpTemplate rabbit;

    private final ObjectMapper mapper = new JacksonConfiguration().objectMapper();

    @Test
    void updateConfigPublishesControlSignal() throws Exception {
        ComponentController controller = new ComponentController(
            rabbit,
            new InMemoryIdempotencyStore(),
            mapper,
            controlPlaneProperties());
        ComponentController.ConfigUpdateRequest request =
            new ComponentController.ConfigUpdateRequest("idem", Map.of("enabled", true), null, "sw1");

        ResponseEntity<ControlResponse> response = controller.updateConfig("generator", "c1", request);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(rabbit).convertAndSend(eq("ph.control"),
            eq(ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, "sw1", "generator", "c1")), captor.capture());
        ControlSignal signal = mapper.readValue(captor.getValue(), ControlSignal.class);
        assertThat(signal.type()).isEqualTo(ControlPlaneSignals.CONFIG_UPDATE);
        assertThat(signal.scope().role()).isEqualTo("generator");
        assertThat(signal.scope().instance()).isEqualTo("c1");
        assertThat(signal.scope().swarmId()).isEqualTo("sw1");
        assertThat(signal.idempotencyKey()).isEqualTo("idem");
        assertThat(signal.data()).isNotNull();
        assertThat(signal.data()).containsKey("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) signal.data().get("data");
        assertThat(data).containsEntry("enabled", true);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().watch().successTopic())
            .isEqualTo(ControlPlaneRouting.event("outcome", ControlPlaneSignals.CONFIG_UPDATE,
                new ConfirmationScope("sw1", "generator", "c1")));
    }

    @Test
    void configUpdateIsIdempotent() {
        ComponentController controller = new ComponentController(
            rabbit,
            new InMemoryIdempotencyStore(),
            mapper,
            controlPlaneProperties());
        ComponentController.ConfigUpdateRequest request =
            new ComponentController.ConfigUpdateRequest("idem", Map.of(), null, null);

        ResponseEntity<ControlResponse> first = controller.updateConfig("processor", "p1", request);
        ResponseEntity<ControlResponse> second = controller.updateConfig("processor", "p1", request);

        verify(rabbit, times(1)).convertAndSend(eq("ph.control"),
            eq(ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, "ALL", "processor", "p1")), anyString());
        assertThat(first.getBody()).isNotNull();
        assertThat(second.getBody()).isNotNull();
        assertThat(first.getBody().correlationId()).isEqualTo(second.getBody().correlationId());
    }

    @Test
    void concurrentConfigUpdatesReuseCorrelation() throws Exception {
        ComponentController controller = new ComponentController(
            rabbit,
            new InMemoryIdempotencyStore(),
            mapper,
            controlPlaneProperties());
        ComponentController.ConfigUpdateRequest request =
            new ComponentController.ConfigUpdateRequest("idem", Map.of(), null, "sw1");

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<ResponseEntity<ControlResponse>> first = executor.submit(() -> {
            start.await();
            return controller.updateConfig("processor", "p1", request);
        });
        Future<ResponseEntity<ControlResponse>> second = executor.submit(() -> {
            start.await();
            return controller.updateConfig("processor", "p1", request);
        });

        start.countDown();
        ResponseEntity<ControlResponse> response1 = first.get(5, TimeUnit.SECONDS);
        ResponseEntity<ControlResponse> response2 = second.get(5, TimeUnit.SECONDS);
        executor.shutdownNow();

        verify(rabbit, times(1)).convertAndSend(eq("ph.control"),
            eq(ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, "sw1", "processor", "p1")), anyString());
        assertThat(response1.getBody()).isNotNull();
        assertThat(response2.getBody()).isNotNull();
        assertThat(response1.getBody().correlationId()).isEqualTo(response2.getBody().correlationId());
    }

    private static ControlPlaneProperties controlPlaneProperties() {
        ControlPlaneProperties properties = new ControlPlaneProperties();
        properties.setExchange("ph.control");
        properties.setControlQueuePrefix("ph.control.manager");
        properties.setSwarmId("default");
        properties.setInstanceId("orch-instance");
        properties.getManager().setRole("orchestrator");
        return properties;
    }
}
