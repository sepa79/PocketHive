package io.pockethive.orchestrator.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
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
import io.pockethive.swarm.model.lifecycle.ControlResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
	        SwarmStore store = storeWithSwarm(mapper, SWARM_ID, TEMPLATE_ID, RUN_ID);
	        ComponentController controller = new ComponentController(
	            publisher,
	            operationDispatch(store),
	            store,
	            controlPlaneProperties(),
                endpointAuthorization(store));
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
	        assertThat(response.getBody()).isNotNull();
	        assertThat(response.getBody().outcomeTopic())
	            .isEqualTo(ControlPlaneRouting.event("outcome", ControlPlaneSignals.CONFIG_UPDATE,
	                new ConfirmationScope(SWARM_ID, "orchestrator", "orch-instance")));
    }

    @Test
	    void configUpdateIsIdempotent() {
	        SwarmStore store = storeWithSwarm(mapper, SWARM_ID, TEMPLATE_ID, RUN_ID);
	        ComponentController controller = new ComponentController(
	            publisher,
	            operationDispatch(store),
	            store,
	            controlPlaneProperties(),
                endpointAuthorization(store));
        ComponentController.ConfigUpdateRequest request =
            new ComponentController.ConfigUpdateRequest("idem", Map.of(), null, SWARM_ID);

        ResponseEntity<ControlResponse> first = controller.updateConfig("processor", "p1", request);
        ResponseEntity<ControlResponse> second = controller.updateConfig("processor", "p1", request);

        verify(publisher, times(1)).publishSignal(any(SignalMessage.class));
        assertThat(first.getBody()).isNotNull();
        assertThat(second.getBody()).isNotNull();
        assertThat(first.getBody().correlationId()).isEqualTo(second.getBody().correlationId());
    }

    @Test
	    void concurrentConfigUpdatesReuseCorrelation() throws Exception {
	        SwarmStore store = storeWithSwarm(mapper, SWARM_ID, TEMPLATE_ID, RUN_ID);
	        ComponentController controller = new ComponentController(
	            publisher,
	            operationDispatch(store),
	            store,
	            controlPlaneProperties(),
                endpointAuthorization(store));
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

    @Test
    void updateConfigRejectsScopedAdminOutsideGrantedFolder() {
        SwarmStore store = storeWithSwarm(mapper, "prod-swarm", "tpl-prod", RUN_ID, "prod/tpl-prod", "prod");
        ComponentController controller = new ComponentController(
            publisher,
            operationDispatch(store),
            store,
            controlPlaneProperties(),
            endpointAuthorization(store));

        try {
            OrchestratorCurrentUserHolder.set(userWith(
                PocketHivePermissionIds.ALL,
                PocketHiveResourceTypes.FOLDER,
                "demo"));
            assertThatThrownBy(() -> controller.updateConfig(
                "processor",
                "p1",
                new ComponentController.ConfigUpdateRequest("idem", Map.of("enabled", true), null, "prod-swarm")))
                .hasMessageContaining("403 FORBIDDEN");
        } finally {
            OrchestratorCurrentUserHolder.clear();
        }
    }

    private static SwarmStore storeWithSwarm(ObjectMapper mapper, String swarmId, String templateId, String runId) {
        return storeWithSwarm(mapper, swarmId, templateId, runId, "demo/" + templateId, "demo");
    }

    private static SwarmStore storeWithSwarm(ObjectMapper mapper,
                                             String swarmId,
                                             String templateId,
                                             String runId,
                                             String bundlePath,
                                             String folderPath) {
        SwarmStore store = new SwarmStore();
        Swarm swarm = new Swarm(swarmId, "controller-1", "container-1", runId);
        swarm.attachTemplate(new SwarmTemplateMetadata(templateId, "swarm-controller:latest", java.util.List.of(), bundlePath, folderPath));
        store.register(swarm);
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
        status.set("runtime", mapper.valueToTree(Map.of("templateId", templateId, "runId", runId)));
        status.putNull("correlationId");
        status.putNull("idempotencyKey");
        var data = status.putObject("data");
        data.put("enabled", true);
        data.putObject("context");
        store.cacheControllerStatusFull(swarmId, status, java.time.Instant.now());
        return store;
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

    private static ControlPlaneProperties controlPlaneProperties() {
        ControlPlaneProperties properties = new ControlPlaneProperties();
        properties.setExchange("ph.control");
        properties.setControlQueuePrefix("ph.control.manager");
        properties.setSwarmId("default");
        properties.setInstanceId("orch-instance");
        properties.getManager().setRole("orchestrator");
        return properties;
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
}
