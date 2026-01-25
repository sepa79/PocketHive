package io.pockethive.orchestrator.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ConfirmationScope;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.messaging.SignalMessage;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.spring.ControlPlaneProperties;
import io.pockethive.orchestrator.infra.InMemoryIdempotencyStore;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.orchestrator.domain.SwarmTemplateMetadata;
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
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class ComponentControllerTest {

    @Mock
    ControlPlanePublisher publisher;

    private static final String SWARM_ID = "sw1";
    private static final String TEMPLATE_ID = "tpl-1";
    private static final String RUN_ID = "run-1";

    private final ObjectMapper mapper = new JacksonConfiguration().objectMapper();

    @Test
    void updateConfigPublishesControlSignal() throws Exception {
        SwarmStore store = storeWithSwarm(SWARM_ID, TEMPLATE_ID, RUN_ID);
        ComponentController controller = new ComponentController(
            publisher,
            new InMemoryIdempotencyStore(),
            store,
            controlPlaneProperties());
        ComponentController.ConfigUpdateRequest request =
            new ComponentController.ConfigUpdateRequest("idem", Map.of("enabled", true), null, SWARM_ID);

        ResponseEntity<ControlResponse> response = controller.updateConfig("generator", "c1", request);

        ArgumentCaptor<SignalMessage> captor = ArgumentCaptor.forClass(SignalMessage.class);
        verify(publisher).publishSignal(captor.capture());
        SignalMessage message = captor.getValue();
        assertThat(message.routingKey())
            .isEqualTo(ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, "sw1", "generator", "c1"));
        assertThat(message.payload()).isInstanceOf(String.class);
        ControlSignal signal = mapper.readValue(message.payload().toString(), ControlSignal.class);
        assertThat(signal.type()).isEqualTo(ControlPlaneSignals.CONFIG_UPDATE);
        assertThat(signal.scope().role()).isEqualTo("generator");
        assertThat(signal.scope().instance()).isEqualTo("c1");
        assertThat(signal.scope().swarmId()).isEqualTo(SWARM_ID);
        assertThat(signal.idempotencyKey()).isEqualTo("idem");
        assertThat(signal.data()).isNotNull();
        assertThat(signal.data()).containsEntry("enabled", true);
        assertThat(signal.runtime()).containsEntry("templateId", TEMPLATE_ID);
        assertThat(signal.runtime()).containsEntry("runId", RUN_ID);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().watch().successTopic())
            .isEqualTo(ControlPlaneRouting.event("outcome", ControlPlaneSignals.CONFIG_UPDATE,
                new ConfirmationScope(SWARM_ID, "generator", "c1")));
    }

    @Test
    void configUpdateIsIdempotent() {
        SwarmStore store = new SwarmStore();
        ComponentController controller = new ComponentController(
            publisher,
            new InMemoryIdempotencyStore(),
            store,
            controlPlaneProperties());
        ComponentController.ConfigUpdateRequest request =
            new ComponentController.ConfigUpdateRequest("idem", Map.of(), null, null);

        ResponseEntity<ControlResponse> first = controller.updateConfig("processor", "p1", request);
        ResponseEntity<ControlResponse> second = controller.updateConfig("processor", "p1", request);

        verify(publisher, times(1)).publishSignal(any(SignalMessage.class));
        assertThat(first.getBody()).isNotNull();
        assertThat(second.getBody()).isNotNull();
        assertThat(first.getBody().correlationId()).isEqualTo(second.getBody().correlationId());
    }

    @Test
    void concurrentConfigUpdatesReuseCorrelation() throws Exception {
        SwarmStore store = storeWithSwarm(SWARM_ID, TEMPLATE_ID, RUN_ID);
        ComponentController controller = new ComponentController(
            publisher,
            new InMemoryIdempotencyStore(),
            store,
            controlPlaneProperties());
        ComponentController.ConfigUpdateRequest request =
            new ComponentController.ConfigUpdateRequest("idem", Map.of(), null, SWARM_ID);

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

        verify(publisher, times(1)).publishSignal(any(SignalMessage.class));
        assertThat(response1.getBody()).isNotNull();
        assertThat(response2.getBody()).isNotNull();
        assertThat(response1.getBody().correlationId()).isEqualTo(response2.getBody().correlationId());
    }

    private static SwarmStore storeWithSwarm(String swarmId, String templateId, String runId) {
        SwarmStore store = new SwarmStore();
        Swarm swarm = new Swarm(swarmId, "controller-1", "container-1", runId);
        swarm.attachTemplate(new SwarmTemplateMetadata(templateId, "swarm-controller:latest", java.util.List.of()));
        store.register(swarm);
        return store;
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
