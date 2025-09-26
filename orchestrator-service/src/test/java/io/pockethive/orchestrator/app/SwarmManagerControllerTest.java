package io.pockethive.orchestrator.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.Topology;
import io.pockethive.control.CommandTarget;
import io.pockethive.control.ConfirmationScope;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.orchestrator.domain.IdempotencyStore;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SwarmManagerControllerTest {

    @Mock
    AmqpTemplate rabbit;
    @Mock
    IdempotencyStore idempotency;

    private final ObjectMapper mapper = new JacksonConfiguration().objectMapper();

    @Test
    void fanOutToggleToAllControllers() throws Exception {
        SwarmRegistry registry = new SwarmRegistry();
        registry.register(new Swarm("sw1", "ctrl-a", "c1"));
        registry.register(new Swarm("sw2", "ctrl-b", "c2"));
        when(idempotency.findCorrelation(eq("sw1"), eq("config-update"), eq("idem-1"))).thenReturn(Optional.empty());
        when(idempotency.findCorrelation(eq("sw2"), eq("config-update"), eq("idem-1"))).thenReturn(Optional.empty());
        SwarmManagerController controller = new SwarmManagerController(registry, rabbit, idempotency, mapper);
        SwarmManagerController.ToggleRequest request =
            new SwarmManagerController.ToggleRequest("idem-1", true, null, null);

        ResponseEntity<SwarmManagerController.FanoutControlResponse> response = controller.updateAll(request);

        ArgumentCaptor<String> payloads = ArgumentCaptor.forClass(String.class);
        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
            eq(ControlPlaneRouting.signal("config-update", "sw1", "swarm-controller", "ctrl-a")), payloads.capture());
        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
            eq(ControlPlaneRouting.signal("config-update", "sw2", "swarm-controller", "ctrl-b")), payloads.capture());
        List<String> sentPayloads = payloads.getAllValues();
        assertThat(sentPayloads).hasSize(2);
        java.util.List<String> swarmIds = new java.util.ArrayList<>();
        for (String json : sentPayloads) {
            ControlSignal signal = mapper.readValue(json, ControlSignal.class);
            swarmIds.add(signal.swarmId());
            assertThat(signal.signal()).isEqualTo("config-update");
            assertThat(signal.commandTarget()).isEqualTo(CommandTarget.SWARM);
            @SuppressWarnings("unchecked")
            var data = (java.util.Map<String, Object>) signal.args().get("data");
            assertThat(data).containsEntry("enabled", true);
            assertThat(data).doesNotContainKey("target");
        }
        assertThat(swarmIds).containsExactlyInAnyOrder("sw1", "sw2");
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().dispatches()).hasSize(2);
    }

    @Test
    void toggleSingleControllerScope() throws Exception {
        SwarmRegistry registry = new SwarmRegistry();
        registry.register(new Swarm("sw9", "ctrl-z", "c9"));
        when(idempotency.findCorrelation(eq("sw9"), eq("config-update"), eq("idem-2"))).thenReturn(Optional.empty());
        SwarmManagerController controller = new SwarmManagerController(registry, rabbit, idempotency, mapper);
        SwarmManagerController.ToggleRequest request =
            new SwarmManagerController.ToggleRequest("idem-2", false, null, CommandTarget.INSTANCE);

        ResponseEntity<SwarmManagerController.FanoutControlResponse> response = controller.updateOne("sw9", request);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE),
            eq(ControlPlaneRouting.signal("config-update", "sw9", "swarm-controller", "ctrl-z")), payload.capture());
        ControlSignal signal = mapper.readValue(payload.getValue(), ControlSignal.class);
        assertThat(signal.commandTarget()).isEqualTo(CommandTarget.INSTANCE);
        @SuppressWarnings("unchecked")
        var data = (java.util.Map<String, Object>) signal.args().get("data");
        assertThat(data).containsEntry("enabled", false);
        assertThat(data).doesNotContainKey("target");
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().dispatches()).hasSize(1);
        SwarmManagerController.Dispatch dispatch = response.getBody().dispatches().getFirst();
        ConfirmationScope scope = new ConfirmationScope("sw9", "swarm-controller", "ctrl-z");
        assertThat(dispatch.response().watch().successTopic())
            .isEqualTo(ControlPlaneRouting.event("ready.config-update", scope));
    }

    @Test
    void deserializesLegacyToggleWithoutCommandTarget() throws Exception {
        String json = """
            {
                "idempotencyKey": "idem-legacy",
                "enabled": true
            }
            """;
        SwarmManagerController.ToggleRequest request =
            mapper.readValue(json, SwarmManagerController.ToggleRequest.class);

        assertThat(request.commandTarget()).isEqualTo(CommandTarget.SWARM);
    }

}
