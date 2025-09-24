package io.pockethive.orchestrator.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.Topology;
import io.pockethive.control.CommandTarget;
import io.pockethive.control.ControlSignal;
import io.pockethive.orchestrator.domain.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * REST endpoint for component-level actions.
 */
@RestController
@RequestMapping("/api/components")
public class ComponentController {
    private static final long CONFIG_UPDATE_TIMEOUT_MS = 60_000L;
    private static final Logger log = LoggerFactory.getLogger(ComponentController.class);

    private final AmqpTemplate rabbit;
    private final IdempotencyStore idempotency;
    private final ObjectMapper json;

    public ComponentController(AmqpTemplate rabbit, IdempotencyStore idempotency, ObjectMapper json) {
        this.rabbit = rabbit;
        this.idempotency = idempotency;
        this.json = json;
    }

    @PostMapping("/{role}/{instance}/config")
    public ResponseEntity<ControlResponse> updateConfig(@PathVariable String role,
                                                        @PathVariable String instance,
                                                        @RequestBody ConfigUpdateRequest request) {
        String path = "/api/components/" + role + "/" + instance + "/config";
        logRestRequest("POST", path, request);
        String scope = scope(role, instance, request.swarmId());
        ResponseEntity<ControlResponse> response = idempotency.findCorrelation(scope, "config-update", request.idempotencyKey())
            .map(correlation -> {
                log.info("[CTRL] reuse config-update role={} instance={} correlation={} idempotencyKey={} scope={}",
                    role, instance, correlation, request.idempotencyKey(), scope);
                return accepted(correlation, request.idempotencyKey(), role, instance);
            })
            .orElseGet(() -> {
                String correlation = UUID.randomUUID().toString();
                ControlSignal payload = ControlSignal.forInstance("config-update", request.swarmId(), role, instance,
                    correlation, request.idempotencyKey(),
                    commandTargetFrom(request), request.target(), argsFrom(request));
                String jsonPayload = toJson(payload);
                sendControl(routingKey(role, instance), jsonPayload, "config-update");
                idempotency.record(scope, "config-update", request.idempotencyKey(), correlation);
                log.info("[CTRL] issue config-update role={} instance={} correlation={} idempotencyKey={} scope={}",
                    role, instance, correlation, request.idempotencyKey(), scope);
                return accepted(correlation, request.idempotencyKey(), role, instance);
            });
        logRestResponse("POST", path, response);
        return response;
    }

    private static String routingKey(String role, String instance) {
        return "sig.config-update." + role + "." + instance;
    }

    private static String scope(String role, String instance, String swarmId) {
        if (swarmId != null && !swarmId.isBlank()) {
            return swarmId;
        }
        return role + ":" + instance;
    }

    private ResponseEntity<ControlResponse> accepted(String correlationId, String idempotencyKey,
                                                      String role, String instance) {
        ControlResponse.Watch watch = new ControlResponse.Watch(
            "ev.ready.config-update." + role + "." + instance,
            "ev.error.config-update." + role + "." + instance
        );
        ControlResponse response = new ControlResponse(correlationId, idempotencyKey, watch, CONFIG_UPDATE_TIMEOUT_MS);
        return ResponseEntity.accepted().body(response);
    }

    private Map<String, Object> argsFrom(ConfigUpdateRequest request) {
        Map<String, Object> patch = request.patch();
        Map<String, Object> args = new LinkedHashMap<>();
        if (patch != null && !patch.isEmpty()) {
            args.put("data", patch);
        }
        return args.isEmpty() ? null : args;
    }

    private CommandTarget commandTargetFrom(ConfigUpdateRequest request) {
        return request.commandTarget();
    }

    public record ConfigUpdateRequest(String idempotencyKey,
                                      Map<String, Object> patch,
                                      String notes,
                                      String swarmId,
                                      String target,
                                      String scope,
                                      CommandTarget commandTarget) {

        public ConfigUpdateRequest {
            if (commandTarget == null && scope != null && !scope.isBlank()) {
                commandTarget = commandTargetFromLegacy(scope);
            }
            if (commandTarget == null && target != null && !target.isBlank()) {
                commandTarget = commandTargetFromLegacy(target);
            }
            if (commandTarget == null) {
                throw new IllegalArgumentException("commandTarget is required");
            }
        }

        private static CommandTarget commandTargetFromLegacy(String scope) {
            return switch (scope.toLowerCase()) {
                case "swarm" -> CommandTarget.SWARM;
                case "role" -> CommandTarget.ROLE;
                case "instance", "controller" -> CommandTarget.INSTANCE;
                case "all" -> CommandTarget.ALL;
                default -> null;
            };
        }
    }

    private String toJson(ControlSignal signal) {
        try {
            return json.writeValueAsString(signal);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize control signal %s for role %s".formatted(
                signal.signal(), signal.role()), e);
        }
    }

    private void sendControl(String routingKey, String payload, String context) {
        String label = (context == null || context.isBlank()) ? "SEND" : "SEND " + context;
        log.info("[CTRL] {} rk={} payload={}", label, routingKey, snippet(payload));
        rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, routingKey, payload);
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

