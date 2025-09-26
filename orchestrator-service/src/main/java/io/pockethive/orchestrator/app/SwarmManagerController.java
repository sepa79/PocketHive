package io.pockethive.orchestrator.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.Topology;
import io.pockethive.control.CommandTarget;
import io.pockethive.control.ControlSignal;
import io.pockethive.control.ConfirmationScope;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.orchestrator.domain.IdempotencyStore;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST surface for controller-level enable toggles.
 */
@RestController
@RequestMapping("/api/swarm-managers")
public class SwarmManagerController {
    private static final Logger log = LoggerFactory.getLogger(SwarmManagerController.class);
    private static final long CONFIG_UPDATE_TIMEOUT_MS = 60_000L;

    private final SwarmRegistry registry;
    private final AmqpTemplate rabbit;
    private final IdempotencyStore idempotency;
    private final ObjectMapper json;

    public SwarmManagerController(SwarmRegistry registry,
                                  AmqpTemplate rabbit,
                                  IdempotencyStore idempotency,
                                  ObjectMapper json) {
        this.registry = registry;
        this.rabbit = rabbit;
        this.idempotency = idempotency;
        this.json = json;
    }

    @PostMapping("/enabled")
    public ResponseEntity<FanoutControlResponse> updateAll(@RequestBody ToggleRequest request) {
        String path = "/api/swarm-managers/enabled";
        logRestRequest("POST", path, request);
        FanoutControlResponse body = dispatch(registry.all(), request);
        ResponseEntity<FanoutControlResponse> response = ResponseEntity.accepted().body(body);
        logRestResponse("POST", path, response);
        return response;
    }

    @PostMapping("/{swarmId}/enabled")
    public ResponseEntity<FanoutControlResponse> updateOne(@PathVariable String swarmId,
                                                           @RequestBody ToggleRequest request) {
        String path = "/api/swarm-managers/" + swarmId + "/enabled";
        logRestRequest("POST", path, request);
        ResponseEntity<FanoutControlResponse> response = registry.find(swarmId)
            .map(swarm -> ResponseEntity.accepted().body(dispatch(List.of(swarm), request)))
            .orElseGet(() -> ResponseEntity.notFound().build());
        logRestResponse("POST", path, response);
        return response;
    }

    private FanoutControlResponse dispatch(Iterable<Swarm> swarms, ToggleRequest request) {
        List<Dispatch> dispatches = new ArrayList<>();
        for (Swarm swarm : swarms) {
            if (swarm == null || swarm.getInstanceId() == null || swarm.getInstanceId().isBlank()) {
                continue;
            }
            String swarmId = swarm.getId();
            String swarmSegment = segmentOrAll(swarmId);
            String scope = swarmId;
            idempotency.findCorrelation(scope, "config-update", request.idempotencyKey())
                .ifPresentOrElse(correlation -> dispatches.add(new Dispatch(swarmSegment, swarm.getInstanceId(),
                        accepted(correlation, request.idempotencyKey(), swarmSegment, swarm.getInstanceId()), true)),
                    () -> {
                        String correlation = UUID.randomUUID().toString();
                        ControlSignal payload = ControlSignal.forInstance(
                            "config-update",
                            swarmId,
                            "swarm-controller",
                            swarm.getInstanceId(),
                            correlation,
                            request.idempotencyKey(),
                            request.commandTarget(),
                            argsFor(request));
                        sendControl(routingKey(swarmSegment, swarm.getInstanceId()), toJson(payload), request.commandTarget());
                        idempotency.record(scope, "config-update", request.idempotencyKey(), correlation);
                        dispatches.add(new Dispatch(swarmSegment, swarm.getInstanceId(),
                            accepted(correlation, request.idempotencyKey(), swarmSegment, swarm.getInstanceId()), false));
                    });
        }
        return new FanoutControlResponse(dispatches);
    }

    private static String routingKey(String swarmSegment, String instanceId) {
        return ControlPlaneRouting.signal("config-update", swarmSegment, "swarm-controller", instanceId);
    }

    private Map<String, Object> argsFor(ToggleRequest request) {
        Map<String, Object> args = new LinkedHashMap<>();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", request.enabled());
        args.put("data", data);
        return args;
    }

    private ControlResponse accepted(String correlationId,
                                     String idempotencyKey,
                                     String swarmSegment,
                                     String instanceId) {
        ConfirmationScope scope = new ConfirmationScope(swarmSegment, "swarm-controller", instanceId);
        ControlResponse.Watch watch = new ControlResponse.Watch(
            ControlPlaneRouting.event("ready.config-update", scope),
            ControlPlaneRouting.event("error.config-update", scope)
        );
        return new ControlResponse(correlationId, idempotencyKey, watch, CONFIG_UPDATE_TIMEOUT_MS);
    }

    private void sendControl(String routingKey, String payload, CommandTarget context) {
        String label = context == null ? "SEND" : "SEND " + context.json();
        log.info("[CTRL] {} rk={} payload={}", label, routingKey, snippet(payload));
        rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, routingKey, payload);
    }

    private String toJson(ControlSignal signal) {
        try {
            return json.writeValueAsString(signal);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize control signal %s for swarm %s".formatted(
                signal.signal(), signal.swarmId()), e);
        }
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
            return text.substring(0, 300) + "...";
        }
        return text;
    }

    private static String snippet(String payload) {
        if (payload == null) {
            return "";
        }
        String trimmed = payload.strip();
        if (trimmed.length() > 300) {
            return trimmed.substring(0, 300) + "...";
        }
        return trimmed;
    }

    public record ToggleRequest(String idempotencyKey,
                                 Boolean enabled,
                                 String notes,
                                 CommandTarget commandTarget) {
        public ToggleRequest {
            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                throw new IllegalArgumentException("idempotencyKey is required");
            }
            if (enabled == null) {
                throw new IllegalArgumentException("enabled flag is required");
            }
            if (commandTarget == null) {
                commandTarget = CommandTarget.SWARM;
            }
            if (commandTarget != CommandTarget.SWARM && commandTarget != CommandTarget.INSTANCE) {
                throw new IllegalArgumentException("commandTarget must be swarm or instance");
            }
        }
    }

    public record FanoutControlResponse(List<Dispatch> dispatches) {}

    public record Dispatch(String swarm, String instanceId, ControlResponse response, boolean reused) {}

    private static String segmentOrAll(String value) {
        return (value == null || value.isBlank()) ? "ALL" : value;
    }
}
