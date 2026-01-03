package io.pockethive.orchestrator.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ConfirmationScope;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.messaging.SignalMessage;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.spring.ControlPlaneProperties;
import io.pockethive.orchestrator.domain.IdempotencyStore;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class SwarmManagerControllerTest {

    @Mock
    ControlPlanePublisher publisher;

    @Mock
    IdempotencyStore idempotency;

    private final ObjectMapper mapper = new JacksonConfiguration().objectMapper();

    @Test
    void fanOutToggleToAllControllers() throws Exception {
        SwarmRegistry registry = new SwarmRegistry();
        registry.register(new Swarm("sw1", "ctrl-a", "c1", "run-1"));
        registry.register(new Swarm("sw2", "ctrl-b", "c2", "run-2"));
        when(idempotency.reserve(eq("sw1"), eq(ControlPlaneSignals.CONFIG_UPDATE), eq("idem-1"), anyString()))
            .thenReturn(Optional.empty());
        when(idempotency.reserve(eq("sw2"), eq(ControlPlaneSignals.CONFIG_UPDATE), eq("idem-1"), anyString()))
            .thenReturn(Optional.empty());
        SwarmManagerController controller = new SwarmManagerController(
            registry,
            publisher,
            idempotency,
            controlPlaneProperties());
        SwarmManagerController.ToggleRequest request =
            new SwarmManagerController.ToggleRequest("idem-1", true, null);

        ResponseEntity<SwarmManagerController.FanoutControlResponse> response = controller.updateAll(request);

        ArgumentCaptor<SignalMessage> payloads = ArgumentCaptor.forClass(SignalMessage.class);
        verify(publisher, org.mockito.Mockito.times(2)).publishSignal(payloads.capture());
        List<SignalMessage> sentPayloads = payloads.getAllValues();
        assertThat(sentPayloads).hasSize(2);
        List<String> swarmIds = new java.util.ArrayList<>();
        for (SignalMessage message : sentPayloads) {
            assertThat(message.payload()).isInstanceOf(String.class);
            ControlSignal signal = mapper.readValue(message.payload().toString(), ControlSignal.class);
            swarmIds.add(signal.scope().swarmId());
            assertThat(signal.type()).isEqualTo(ControlPlaneSignals.CONFIG_UPDATE);
            assertThat(signal.data()).containsEntry("enabled", true);
            assertThat(signal.data()).doesNotContainKey("target");
        }
        assertThat(swarmIds).containsExactlyInAnyOrder("sw1", "sw2");
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().dispatches()).hasSize(2);
    }

    @Test
    void toggleSingleControllerScope() throws Exception {
        SwarmRegistry registry = new SwarmRegistry();
        registry.register(new Swarm("sw9", "ctrl-z", "c9", "run-9"));
        when(idempotency.reserve(eq("sw9"), eq(ControlPlaneSignals.CONFIG_UPDATE), eq("idem-2"), anyString()))
            .thenReturn(Optional.empty());
        SwarmManagerController controller = new SwarmManagerController(
            registry,
            publisher,
            idempotency,
            controlPlaneProperties());
        SwarmManagerController.ToggleRequest request =
            new SwarmManagerController.ToggleRequest("idem-2", false, null);

        ResponseEntity<SwarmManagerController.FanoutControlResponse> response = controller.updateOne("sw9", request);

        ArgumentCaptor<SignalMessage> payload = ArgumentCaptor.forClass(SignalMessage.class);
        verify(publisher).publishSignal(payload.capture());
        SignalMessage message = payload.getValue();
        assertThat(message.routingKey())
            .isEqualTo(ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, "sw9", "swarm-controller", "ctrl-z"));
        assertThat(message.payload()).isInstanceOf(String.class);
        ControlSignal signal = mapper.readValue(message.payload().toString(), ControlSignal.class);
        assertThat(signal.data()).containsEntry("enabled", false);
        assertThat(signal.data()).doesNotContainKey("target");
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().dispatches()).hasSize(1);
        SwarmManagerController.Dispatch dispatch = response.getBody().dispatches().getFirst();
        ConfirmationScope scope = new ConfirmationScope("sw9", "swarm-controller", "ctrl-z");
        assertThat(dispatch.response().watch().successTopic())
            .isEqualTo(ControlPlaneRouting.event("outcome", ControlPlaneSignals.CONFIG_UPDATE, scope));
    }

    @Test
    void deserializesToggle() throws Exception {
        String json = """
            {
                "idempotencyKey": "idem-legacy",
                "enabled": true
            }
            """;
        SwarmManagerController.ToggleRequest request =
            mapper.readValue(json, SwarmManagerController.ToggleRequest.class);

        assertThat(request.idempotencyKey()).isEqualTo("idem-legacy");
        assertThat(request.enabled()).isTrue();
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
