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
import io.pockethive.orchestrator.domain.IdempotencyStore;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmStateStore;
import io.pockethive.orchestrator.domain.SwarmStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * REST endpoint for component-level actions.
 */
@RestController
@RequestMapping("/api/components")
public class ComponentController {
    private static final long CONFIG_UPDATE_TIMEOUT_MS = 60_000L;
    private static final Logger log = LoggerFactory.getLogger(ComponentController.class);

    private final ControlPlanePublisher controlPublisher;
    private final IdempotencyStore idempotency;
    private final String originInstanceId;
    private final SwarmStore store;
    private final SwarmStateStore stateStore;

    public ComponentController(
        ControlPlanePublisher controlPublisher,
        IdempotencyStore idempotency,
        SwarmStore store,
        SwarmStateStore stateStore,
        ControlPlaneProperties controlPlaneProperties) {
        this.controlPublisher = controlPublisher;
        this.idempotency = idempotency;
        this.store = store;
        this.stateStore = stateStore;
        this.originInstanceId = requireOrigin(controlPlaneProperties);
    }

    @PostMapping("/{role}/{instance}/config")
    public ResponseEntity<ControlResponse> updateConfig(@PathVariable String role,
                                                        @PathVariable String instance,
                                                        @RequestBody ConfigUpdateRequest request) {
        String path = "/api/components/" + role + "/" + instance + "/config";
        logRestRequest("POST", path, request);
        String swarmId = request.swarmId();
        String scope = scope(role, instance, swarmId);
        String swarmSegment = segmentOrAll(swarmId);
        String newCorrelation = UUID.randomUUID().toString();
        Optional<String> existing = idempotency.reserve(scope, ControlPlaneSignals.CONFIG_UPDATE, request.idempotencyKey(), newCorrelation);
        ResponseEntity<ControlResponse> response;
        if (existing.isPresent()) {
            String correlation = existing.get();
            log.info("[CTRL] reuse config-update role={} instance={} correlation={} idempotencyKey={} scope={}",
                role, instance, correlation, request.idempotencyKey(), scope);
            response = accepted(correlation, request.idempotencyKey(), swarmSegment, role, instance);
        } else {
            Map<String, Object> patch = request.patch();
            if (patch != null && patch.isEmpty()) {
                patch = null;
            }
            ControlSignal payload = ControlSignals.configUpdate(
                originInstanceId,
                ControlScope.forInstance(swarmId, role, instance),
                newCorrelation,
                request.idempotencyKey(),
                runtimeMetaForSwarmOrNull(swarmId),
                patch);
            String jsonPayload = toJson(payload);
            try {
                sendControl(routingKey(swarmSegment, role, instance), jsonPayload, ControlPlaneSignals.CONFIG_UPDATE);
            } catch (RuntimeException e) {
                idempotency.rollback(scope, ControlPlaneSignals.CONFIG_UPDATE, request.idempotencyKey(), newCorrelation);
                throw e;
            }
            log.info("[CTRL] issue config-update role={} instance={} correlation={} idempotencyKey={} scope={}",
                role, instance, newCorrelation, request.idempotencyKey(), scope);
            response = accepted(newCorrelation, request.idempotencyKey(), swarmSegment, role, instance);
        }
        logRestResponse("POST", path, response);
        return response;
    }

    private static String routingKey(String swarmId, String role, String instance) {
        return ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, swarmId, role, instance);
    }

    private static String scope(String role, String instance, String swarmId) {
        if (swarmId != null && !swarmId.isBlank()) {
            return swarmId;
        }
        return role + ":" + instance;
    }

    private Map<String, Object> runtimeMetaForSwarmOrNull(String swarmId) {
        if (swarmId == null || swarmId.isBlank()) {
            return null;
        }
        String resolvedSwarmId = swarmId.trim();
        if (ControlScope.ALL.equalsIgnoreCase(resolvedSwarmId)) {
            return null;
        }
        Swarm swarm = store.find(resolvedSwarmId)
            .orElseThrow(() -> new IllegalStateException("Swarm " + resolvedSwarmId + " is not registered"));
        return stateStore.requireRuntimeFromLatestStatusFull(swarm.getId());
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private ResponseEntity<ControlResponse> accepted(String correlationId,
                                                      String idempotencyKey,
                                                      String swarmSegment,
                                                      String role,
                                                      String instance) {
        ConfirmationScope scope = new ConfirmationScope(swarmSegment, role, instance);
        ControlResponse.Watch watch = new ControlResponse.Watch(
            ControlPlaneRouting.event("outcome", ControlPlaneSignals.CONFIG_UPDATE, scope),
            ControlPlaneRouting.event("alert", "alert", scope)
        );
        ControlResponse response = new ControlResponse(correlationId, idempotencyKey, watch, CONFIG_UPDATE_TIMEOUT_MS);
        return ResponseEntity.accepted().body(response);
    }

    private static String segmentOrAll(String swarmId) {
        return (swarmId == null || swarmId.isBlank()) ? "ALL" : swarmId;
    }

    public record ConfigUpdateRequest(String idempotencyKey,
                                      Map<String, Object> patch,
                                      String notes,
                                      String swarmId) {
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
