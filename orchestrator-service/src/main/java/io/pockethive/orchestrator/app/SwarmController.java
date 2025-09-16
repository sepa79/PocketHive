package io.pockethive.orchestrator.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.Topology;
import io.pockethive.orchestrator.domain.ControlSignal;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmHealth;
import io.pockethive.orchestrator.domain.SwarmCreateTracker;
import io.pockethive.orchestrator.domain.SwarmCreateTracker.Pending;
import io.pockethive.orchestrator.domain.IdempotencyStore;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import io.pockethive.orchestrator.domain.SwarmStatus;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

import io.pockethive.util.BeeNameGenerator;

/**
 * REST endpoints for swarm lifecycle operations.
 */
@RestController
@RequestMapping("/api/swarms")
public class SwarmController {
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
        return idempotentSend("swarm-create", swarmId, req.idempotencyKey(), 120_000L, corr -> {
            String instanceId = BeeNameGenerator.generate("swarm-controller", swarmId);
            Swarm swarm = lifecycle.startSwarm(swarmId, instanceId);
            creates.register(swarm.getInstanceId(), new Pending(swarmId, corr, req.idempotencyKey()));
        });
    }

    @PostMapping("/{swarmId}/start")
    public ResponseEntity<ControlResponse> start(@PathVariable String swarmId, @RequestBody ControlRequest req) {
        return sendSignal("swarm-start", swarmId, req.idempotencyKey(), 180_000L);
    }

    @PostMapping("/{swarmId}/stop")
    public ResponseEntity<ControlResponse> stop(@PathVariable String swarmId, @RequestBody ControlRequest req) {
        return sendSignal("swarm-stop", swarmId, req.idempotencyKey(), 90_000L);
    }

    @PostMapping("/{swarmId}/remove")
    public ResponseEntity<ControlResponse> remove(@PathVariable String swarmId, @RequestBody ControlRequest req) {
        return sendSignal("swarm-remove", swarmId, req.idempotencyKey(), 180_000L);
    }

    private ResponseEntity<ControlResponse> sendSignal(String signal, String swarmId, String idempotencyKey, long timeoutMs) {
        return idempotentSend(signal, swarmId, idempotencyKey, timeoutMs, corr -> {
            ControlSignal payload = ControlSignal.forSwarm(signal, swarmId, corr, idempotencyKey);
            rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, "sig." + signal + "." + swarmId, toJson(payload));
        });
    }

    private ResponseEntity<ControlResponse> idempotentSend(String signal, String swarmId, String idempotencyKey,
                                                           long timeoutMs, java.util.function.Consumer<String> action) {
        return idempotency.findCorrelation(swarmId, signal, idempotencyKey)
            .map(corr -> {
                ControlResponse resp = new ControlResponse(corr, idempotencyKey,
                    new ControlResponse.Watch("ev.ready." + signal + "." + swarmId,
                        "ev.error." + signal + "." + swarmId), timeoutMs);
                return ResponseEntity.accepted().body(resp);
            })
            .orElseGet(() -> {
                String corr = UUID.randomUUID().toString();
                action.accept(corr);
                idempotency.record(swarmId, signal, idempotencyKey, corr);
                ControlResponse resp = new ControlResponse(corr, idempotencyKey,
                    new ControlResponse.Watch("ev.ready." + signal + "." + swarmId,
                        "ev.error." + signal + "." + swarmId), timeoutMs);
                return ResponseEntity.accepted().body(resp);
            });
    }

    public record ControlRequest(String idempotencyKey, String notes) {}

    @GetMapping("/{swarmId}")
    public ResponseEntity<SwarmView> view(@PathVariable String swarmId) {
        return registry.find(swarmId)
            .map(s -> ResponseEntity.ok(new SwarmView(s.getId(), s.getStatus(), s.getHealth(), s.getHeartbeat())))
            .orElse(ResponseEntity.notFound().build());
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
}
