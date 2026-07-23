package io.pockethive.orchestrator.app;

import io.pockethive.control.ControlSignal;
import io.pockethive.control.ConfirmationScope;
import io.pockethive.control.ControlScope;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.messaging.ControlSignals;
import io.pockethive.controlplane.messaging.SignalMessage;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.spring.ControlPlaneProperties;
import io.pockethive.observability.ControlPlaneJson;
import io.pockethive.orchestrator.auth.OrchestratorEndpointAuthorization;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.swarm.model.lifecycle.ControlResponse;
import io.pockethive.swarm.model.lifecycle.OperationType;
import io.pockethive.swarm.model.lifecycle.SwarmOperation;
import io.pockethive.swarm.model.lifecycle.Target;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * REST endpoint for component-level actions.
 */
@RestController
@RequestMapping("/api/components")
public class ComponentController {
    private static final long CONFIG_UPDATE_TIMEOUT_MS = 60_000L;
    private static final Logger log = LoggerFactory.getLogger(ComponentController.class);

	    private final ControlPlanePublisher controlPublisher;
	    private final OperationDispatchService operations;
	    private final String originInstanceId;
	    private final SwarmStore store;
        private final OrchestratorEndpointAuthorization endpointAuthorization;

	    public ComponentController(
	        ControlPlanePublisher controlPublisher,
	        OperationDispatchService operations,
	        SwarmStore store,
	        ControlPlaneProperties controlPlaneProperties,
            OrchestratorEndpointAuthorization endpointAuthorization) {
	        this.controlPublisher = controlPublisher;
	        this.operations = operations;
	        this.store = store;
	        this.originInstanceId = requireOrigin(controlPlaneProperties);
            this.endpointAuthorization = endpointAuthorization;
	    }

    @PostMapping("/{role}/{instance}/config")
    public ResponseEntity<ControlResponse> updateConfig(@PathVariable String role,
                                                        @PathVariable String instance,
                                                        @RequestBody ConfigUpdateRequest request) {
        String path = "/api/components/" + role + "/" + instance + "/config";
        logRestRequest("POST", path, request);
        String swarmId = requireText("swarmId", request.swarmId());
        String targetRole = requireText("role", role);
        String targetInstance = requireText("instance", instance);
        endpointAuthorization.requireManageSwarm(swarmId);
        if (store.find(swarmId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Target target = new Target(targetRole, targetInstance);
        Map<String, Object> patch = request.patch();
        if (patch != null && patch.isEmpty()) {
            patch = null;
        }
        Map<String, Object> configPatch = patch;
        var enabledExpectation = enabledExpectation(configPatch);
        var reservation = operations.dispatch(
            swarmId,
            OperationType.CONFIG_UPDATE,
            target,
            request.idempotencyKey(),
            Duration.ofMillis(CONFIG_UPDATE_TIMEOUT_MS),
            correlation -> {
	              operations.registerConfigExpectation(correlation, enabledExpectation);
	              ControlSignal payload = ControlSignals.configUpdate(
	                  originInstanceId,
	                  ControlScope.forInstance(swarmId, targetRole, targetInstance),
	                  correlation,
	                  request.idempotencyKey(),
	                  configPatch);
            String jsonPayload = toJson(payload);
              sendControl(routingKey(swarmId, targetRole, targetInstance), jsonPayload, ControlPlaneSignals.CONFIG_UPDATE);
            });
        SwarmOperation operation = reservation.operation();
        log.info("[CTRL] {} config-update role={} instance={} correlation={} idempotencyKey={} swarm={}",
            reservation.reused() ? "reuse" : "issue", targetRole, targetInstance,
            operation.correlationId(), operation.idempotencyKey(), swarmId);
        ResponseEntity<ControlResponse> response = accepted(operation);
        logRestResponse("POST", path, response);
        return response;
    }

    private static io.pockethive.orchestrator.domain.SwarmOperationCoordinator.ConfigEnabledExpectation enabledExpectation(
        Map<String, Object> patch) {
        if (patch == null || !patch.containsKey("enabled")) {
            return io.pockethive.orchestrator.domain.SwarmOperationCoordinator.ConfigEnabledExpectation.UNCHANGED;
        }
        Object value = patch.get("enabled");
        if (!(value instanceof Boolean enabled)) {
            throw new IllegalArgumentException("patch.enabled must be a boolean");
        }
        return io.pockethive.orchestrator.domain.SwarmOperationCoordinator.ConfigEnabledExpectation.fromRequested(enabled);
    }

    private static String routingKey(String swarmId, String role, String instance) {
        return ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, swarmId, role, instance);
    }

    private ResponseEntity<ControlResponse> accepted(SwarmOperation operation) {
        ConfirmationScope scope = new ConfirmationScope(operation.swarmId(), "orchestrator", originInstanceId);
        ControlResponse response = new ControlResponse(
            operation.correlationId(),
            operation.idempotencyKey(),
            "/api/swarms/" + operation.swarmId() + "/operations/" + operation.correlationId(),
            ControlPlaneRouting.event("outcome", ControlPlaneSignals.CONFIG_UPDATE, scope),
            CONFIG_UPDATE_TIMEOUT_MS);
        return ResponseEntity.accepted().body(response);
    }

    public record ConfigUpdateRequest(String idempotencyKey,
                                      Map<String, Object> patch,
                                      String notes,
                                      String swarmId) {
        public ConfigUpdateRequest {
            idempotencyKey = requireText("idempotencyKey", idempotencyKey);
            swarmId = requireText("swarmId", swarmId);
        }
    }

    private static String requireText(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private String toJson(ControlSignal signal) {
        return ControlPlaneJson.write(
            signal,
            "control signal %s for role %s".formatted(
                signal.type(), signal.scope() != null ? signal.scope().role() : "n/a"));
    }

    private void sendControl(String routingKey, String payload, String context) {
        String label = (context == null || context.isBlank()) ? "SEND" : "SEND " + context;
        log.info("[CTRL] {} rk={} payload={}", label, routingKey, snippet(payload));
        controlPublisher.publishSignal(new SignalMessage(routingKey, payload));
    }

    private static String requireOrigin(ControlPlaneProperties properties) {
        String instanceId = properties.getInstanceId();
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalStateException("pockethive.control-plane.identity.instance-id must not be null or blank");
        }
        return instanceId.trim();
    }

    private void logRestRequest(String method, String path, Object body) {
        log.info("[REST] {} {} request={}", method, path, toSafeString(body));
    }

    private void logRestResponse(String method, String path, ResponseEntity<?> response) {
        if (response == null) {
            return;
        }
        log.info("[REST] {} {} -> status={} body={}", method, path, response.getStatusCode(), toSafeString(response.getBody()));
    }

    private static String toSafeString(Object value) {
        if (value == null) {
            return "";
        }
        String text = value.toString();
        if (text.length() > 300) {
            return text.substring(0, 300) + "…";
        }
        return text;
    }

    private static String snippet(String payload) {
        if (payload == null) {
            return "";
        }
        String trimmed = payload.strip();
        if (trimmed.length() > 300) {
            return trimmed.substring(0, 300) + "…";
        }
        return trimmed;
    }
}
