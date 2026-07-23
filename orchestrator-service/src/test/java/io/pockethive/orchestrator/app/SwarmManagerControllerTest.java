package io.pockethive.orchestrator.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.auth.contract.AuthGrantDto;
import io.pockethive.auth.contract.AuthProduct;
import io.pockethive.auth.contract.AuthProvider;
import io.pockethive.auth.contract.AuthenticatedUserDto;
import io.pockethive.auth.contract.PocketHivePermissionIds;
import io.pockethive.auth.contract.PocketHiveResourceTypes;
import io.pockethive.control.ConfirmationScope;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.messaging.SignalMessage;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.spring.ControlPlaneProperties;
import io.pockethive.orchestrator.auth.OrchestratorAuthorization;
import io.pockethive.orchestrator.auth.OrchestratorCurrentUserHolder;
import io.pockethive.orchestrator.auth.OrchestratorEndpointAuthorization;
import io.pockethive.orchestrator.domain.SwarmOperationCoordinator;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.orchestrator.domain.SwarmTemplateMetadata;
import java.util.List;
import java.util.UUID;
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

    private final ObjectMapper mapper = new JacksonConfiguration().objectMapper();

    @Test
    void fanOutToggleToAllControllers() throws Exception {
        SwarmStore registry = new SwarmStore();
        Swarm swarm1 = new Swarm("sw1", "ctrl-a", "c1", "run-1");
        swarm1.attachTemplate(new SwarmTemplateMetadata("tpl-1", "swarm-controller:latest", List.of(), "demo/tpl-1", "demo"));
        registry.register(swarm1);
        cacheStatusFull(mapper, registry, "sw1", "tpl-1", "run-1");
        Swarm swarm2 = new Swarm("sw2", "ctrl-b", "c2", "run-2");
        swarm2.attachTemplate(new SwarmTemplateMetadata("tpl-2", "swarm-controller:latest", List.of(), "demo/tpl-2", "demo"));
        registry.register(swarm2);
        cacheStatusFull(mapper, registry, "sw2", "tpl-2", "run-2");
	        SwarmManagerController controller = new SwarmManagerController(
	            registry,
	            publisher,
	            operationDispatch(registry),
	            controlPlaneProperties(),
                endpointAuthorization(registry));
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
        SwarmStore registry = new SwarmStore();
        Swarm swarm = new Swarm("sw9", "ctrl-z", "c9", "run-9");
        swarm.attachTemplate(new SwarmTemplateMetadata("tpl-9", "swarm-controller:latest", List.of(), "demo/tpl-9", "demo"));
        registry.register(swarm);
	        cacheStatusFull(mapper, registry, "sw9", "tpl-9", "run-9");
	        SwarmManagerController controller = new SwarmManagerController(
	            registry,
	            publisher,
	            operationDispatch(registry),
	            controlPlaneProperties(),
                endpointAuthorization(registry));
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
        ConfirmationScope scope = new ConfirmationScope("sw9", "orchestrator", "orch-instance");
        assertThat(dispatch.response().outcomeTopic())
            .isEqualTo(ControlPlaneRouting.event("outcome", ControlPlaneSignals.CONFIG_UPDATE, scope));
    }

    @Test
    void updateAllRequiresDeploymentAdmin() {
        SwarmStore registry = new SwarmStore();
        SwarmManagerController controller = new SwarmManagerController(
            registry,
            publisher,
            operationDispatch(registry),
            controlPlaneProperties(),
            endpointAuthorization(registry));

        try {
            OrchestratorCurrentUserHolder.set(userWith(
                PocketHivePermissionIds.ALL,
                PocketHiveResourceTypes.FOLDER,
                "demo"));
            assertThatThrownBy(() -> controller.updateAll(new SwarmManagerController.ToggleRequest("idem-3", true, null)))
                .hasMessageContaining("403 FORBIDDEN");
        } finally {
            OrchestratorCurrentUserHolder.clear();
        }
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

    private static OrchestratorEndpointAuthorization endpointAuthorization(SwarmStore store) {
        return new OrchestratorEndpointAuthorization(new OrchestratorAuthorization(), scenarioClient(), store);
    }

    private static OperationDispatchService operationDispatch(SwarmStore store) {
        return new OperationDispatchService(
            new SwarmOperationCoordinator(),
            org.mockito.Mockito.mock(OperationOutcomePublisher.class),
            store);
    }

    private static ScenarioClient scenarioClient() {
        return new ScenarioClient() {
            @Override
            public io.pockethive.orchestrator.domain.ScenarioPlan fetchScenario(String templateId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ScenarioTemplateDescriptor fetchScenarioTemplate(String templateId) {
                return new ScenarioTemplateDescriptor(templateId, "demo/" + templateId, "demo/" + templateId, "demo", false);
            }

            @Override
            public String prepareScenarioRuntime(String templateId, String swarmId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public io.pockethive.swarm.model.SutEnvironment fetchScenarioSut(String templateId,
                                                                             String sutId,
                                                                             String correlationId,
                                                                             String idempotencyKey) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ResolvedVariables resolveScenarioVariables(String templateId,
                                                             String profileId,
                                                             String sutId,
                                                             String correlationId,
                                                             String idempotencyKey) {
                throw new UnsupportedOperationException();
            }

            @Override
            public io.pockethive.swarm.model.NetworkProfile fetchNetworkProfile(String profileId,
                                                                                String correlationId,
                                                                                String idempotencyKey) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static AuthenticatedUserDto userWith(String permission, String resourceType, String resourceSelector) {
        return new AuthenticatedUserDto(
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            "local-user",
            "Local User",
            true,
            AuthProvider.DEV,
            List.of(new AuthGrantDto(
                AuthProduct.POCKETHIVE,
                permission,
                resourceType,
                resourceSelector
            ))
        );
    }

    private static void cacheStatusFull(ObjectMapper mapper,
                                        SwarmStore store,
                                        String swarmId,
                                        String templateId,
                                        String runId) {
        var status = mapper.createObjectNode();
        status.put("timestamp", java.time.Instant.now().toString());
        status.put("version", "1");
        status.put("kind", "metric");
        status.put("type", "status-full");
        status.put("origin", "swarm-controller-1");
        var scope = status.putObject("scope");
        scope.put("swarmId", swarmId);
        scope.put("role", "swarm-controller");
        scope.put("instance", "controller-1");
        status.set("runtime", mapper.valueToTree(java.util.Map.of("templateId", templateId, "runId", runId)));
        status.putNull("correlationId");
        status.putNull("idempotencyKey");
        var data = status.putObject("data");
        data.put("enabled", true);
        data.putObject("context");
        store.cacheControllerStatusFull(swarmId, status, java.time.Instant.now());
    }
}
