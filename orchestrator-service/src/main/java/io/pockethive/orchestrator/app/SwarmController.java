package io.pockethive.orchestrator.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.Topology;
import io.pockethive.control.ControlSignal;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmHealth;
import io.pockethive.orchestrator.domain.SwarmCreateTracker;
import io.pockethive.orchestrator.domain.SwarmCreateTracker.Pending;
import io.pockethive.orchestrator.domain.SwarmCreateTracker.Phase;
import io.pockethive.orchestrator.domain.IdempotencyStore;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import io.pockethive.orchestrator.domain.SwarmStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import io.pockethive.util.BeeNameGenerator;

/**
 * REST endpoints for swarm lifecycle operations.
 */
@RestController
@RequestMapping("/api/swarms")
public class SwarmController {
    private static final Logger log = LoggerFactory.getLogger(SwarmController.class);
    private final AmqpTemplate rabbit;
    private final ContainerLifecycleManager lifecycle;
    private final SwarmCreateTracker creates;
    private final IdempotencyStore idempotency;
    private final SwarmRegistry registry;
    private final ObjectMapper json;

    public SwarmController(AmqpTemplate rabbit,
                           ContainerLifecycleManager lifecycle,
                           SwarmCreateTracker creates,
                           IdempotencyStore idempotency,
                           SwarmRegistry registry,
                           ObjectMapper json) {
        this.rabbit = rabbit;
        this.lifecycle = lifecycle;
        this.creates = creates;
        this.idempotency = idempotency;
        this.registry = registry;
        this.json = json;
    }

    @PostMapping("/{swarmId}/create")
    public ResponseEntity<ControlResponse> create(@PathVariable String swarmId, @RequestBody ControlRequest req) {
        String path = "/api/swarms/" + swarmId + "/create";
        logRestRequest("POST", path, req);
        Duration timeout = Duration.ofMillis(120_000L);
        ResponseEntity<ControlResponse> response = idempotentSend("swarm-create", swarmId, req.idempotencyKey(), timeout.toMillis(), corr -> {
            String instanceId = BeeNameGenerator.generate("swarm-controller", swarmId);
            Swarm swarm = lifecycle.startSwarm(swarmId, instanceId);
            creates.register(swarm.getInstanceId(), new Pending(
                swarmId,
                swarm.getInstanceId(),
                corr,
                req.idempotencyKey(),
                Phase.CONTROLLER,
                Instant.now().plus(timeout)));
        });
        logRestResponse("POST", path, response);
        return response;
    }

    @PostMapping("/{swarmId}/start")
    public ResponseEntity<ControlResponse> start(@PathVariable String swarmId, @RequestBody ControlRequest req) {
        String path = "/api/swarms/" + swarmId + "/start";
        logRestRequest("POST", path, req);
        ResponseEntity<ControlResponse> response = sendSignal("swarm-start", swarmId, req.idempotencyKey(), 180_000L);
        logRestResponse("POST", path, response);
        return response;
    }

    @PostMapping("/{swarmId}/stop")
    public ResponseEntity<ControlResponse> stop(@PathVariable String swarmId, @RequestBody ControlRequest req) {
        String path = "/api/swarms/" + swarmId + "/stop";
        logRestRequest("POST", path, req);
        ResponseEntity<ControlResponse> response = sendSignal("swarm-stop", swarmId, req.idempotencyKey(), 90_000L);
        logRestResponse("POST", path, response);
        return response;
    }

    @PostMapping("/{swarmId}/remove")
    public ResponseEntity<ControlResponse> remove(@PathVariable String swarmId, @RequestBody ControlRequest req) {
        String path = "/api/swarms/" + swarmId + "/remove";
        logRestRequest("POST", path, req);
        ResponseEntity<ControlResponse> response = sendSignal("swarm-remove", swarmId, req.idempotencyKey(), 180_000L);
        logRestResponse("POST", path, response);
        return response;
    }

    private ResponseEntity<ControlResponse> sendSignal(String signal, String swarmId, String idempotencyKey, long timeoutMs) {
        Duration timeout = Duration.ofMillis(timeoutMs);
        return idempotentSend(signal, swarmId, idempotencyKey, timeoutMs, corr -> {
            ControlSignal payload = ControlSignal.forSwarm(signal, swarmId, corr, idempotencyKey);
            String jsonPayload = toJson(payload);
            String routingKey = "sig." + signal + "." + swarmId;
            sendControl(routingKey, jsonPayload, signal);
            if ("swarm-start".equals(signal)) {
                registry.markStartIssued(swarmId);
                creates.expectStart(swarmId, corr, idempotencyKey, timeout);
            } else if ("swarm-stop".equals(signal)) {
                creates.expectStop(swarmId, corr, idempotencyKey, timeout);
            }
        });
    }

    private ResponseEntity<ControlResponse> idempotentSend(String signal, String swarmId, String idempotencyKey,
                                                           long timeoutMs, java.util.function.Consumer<String> action) {
        return idempotency.findCorrelation(swarmId, signal, idempotencyKey)
            .map(corr -> {
                ControlResponse resp = new ControlResponse(corr, idempotencyKey,
                    new ControlResponse.Watch("ev.ready." + signal + "." + swarmId,
                        "ev.error." + signal + "." + swarmId), timeoutMs);
                log.info("[CTRL] reuse signal={} swarm={} correlation={} idempotencyKey={}", signal, swarmId, corr, idempotencyKey);
                return ResponseEntity.accepted().body(resp);
            })
            .orElseGet(() -> {
                String corr = UUID.randomUUID().toString();
                action.accept(corr);
                idempotency.record(swarmId, signal, idempotencyKey, corr);
                ControlResponse resp = new ControlResponse(corr, idempotencyKey,
                    new ControlResponse.Watch("ev.ready." + signal + "." + swarmId,
                        "ev.error." + signal + "." + swarmId), timeoutMs);
                log.info("[CTRL] issue signal={} swarm={} correlation={} idempotencyKey={} timeoutMs={}",
                    signal, swarmId, corr, idempotencyKey, timeoutMs);
                return ResponseEntity.accepted().body(resp);
            });
    }

    public record ControlRequest(String idempotencyKey, String notes) {}

    @GetMapping("/{swarmId}")
    public ResponseEntity<SwarmView> view(@PathVariable String swarmId) {
        String path = "/api/swarms/" + swarmId;
        logRestRequest("GET", path, null);
        ResponseEntity<SwarmView> response = registry.find(swarmId)
            .map(s -> ResponseEntity.ok(new SwarmView(s.getId(), s.getStatus(), s.getHealth(), s.getHeartbeat())))
            .orElse(ResponseEntity.notFound().build());
        logRestResponse("GET", path, response);
        return response;
    }

    public record SwarmView(String id, SwarmStatus status, SwarmHealth health, java.time.Instant heartbeat) {}

    private String toJson(ControlSignal signal) {
        try {
            return json.writeValueAsString(signal);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize control signal %s for swarm %s".formatted(
                signal.signal(), signal.swarmId()), e);
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
