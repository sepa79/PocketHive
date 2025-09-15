package io.pockethive.orchestrator.app;

import io.pockethive.Topology;
import io.pockethive.orchestrator.domain.ControlSignal;
import io.pockethive.orchestrator.domain.IdempotencyStore;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * REST endpoint for component-level actions.
 */
@RestController
@RequestMapping("/api/components")
public class ComponentController {
    private static final long CONFIG_UPDATE_TIMEOUT_MS = 60_000L;

    private final AmqpTemplate rabbit;
    private final IdempotencyStore idempotency;

    public ComponentController(AmqpTemplate rabbit, IdempotencyStore idempotency) {
        this.rabbit = rabbit;
        this.idempotency = idempotency;
    }

    @PostMapping("/{role}/{instance}/config")
    public ResponseEntity<ControlResponse> updateConfig(@PathVariable String role,
                                                        @PathVariable String instance,
                                                        @RequestBody ConfigUpdateRequest request) {
        String scope = scope(role, instance, request.swarmId());
        return idempotency.findCorrelation(scope, "config-update", request.idempotencyKey())
            .map(correlation -> accepted(correlation, request.idempotencyKey(), role, instance))
            .orElseGet(() -> {
                String correlation = UUID.randomUUID().toString();
                ControlSignal payload = ControlSignal.forInstance("config-update", request.swarmId(), role, instance,
                    correlation, request.idempotencyKey());
                rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, routingKey(role, instance), payload);
                idempotency.record(scope, "config-update", request.idempotencyKey(), correlation);
                return accepted(correlation, request.idempotencyKey(), role, instance);
            });
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

    public record ConfigUpdateRequest(String idempotencyKey,
                                      Map<String, Object> patch,
                                      String notes,
                                      String swarmId) { }
}

