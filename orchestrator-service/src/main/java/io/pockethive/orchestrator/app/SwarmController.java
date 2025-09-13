package io.pockethive.orchestrator.app;

import io.pockethive.Topology;
import io.pockethive.orchestrator.domain.ControlSignal;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmCreateTracker;
import io.pockethive.orchestrator.domain.SwarmCreateTracker.Pending;
import io.pockethive.orchestrator.domain.IdempotencyStore;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

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

    public SwarmController(AmqpTemplate rabbit,
                           ContainerLifecycleManager lifecycle,
                           SwarmCreateTracker creates,
                           IdempotencyStore idempotency) {
        this.rabbit = rabbit;
        this.lifecycle = lifecycle;
        this.creates = creates;
        this.idempotency = idempotency;
    }

    @PostMapping("/{swarmId}/create")
    public ResponseEntity<ControlResponse> create(@PathVariable String swarmId, @RequestBody ControlRequest req) {
        return idempotentSend("swarm-create", swarmId, req.idempotencyKey(), 120_000L, () -> {
            String instanceId = UUID.randomUUID().toString();
            Swarm swarm = lifecycle.startSwarm(swarmId, instanceId);
            creates.register(swarm.getInstanceId(), new Pending(swarmId, currentCorrelation, req.idempotencyKey()));
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
        return idempotentSend(signal, swarmId, idempotencyKey, timeoutMs, () -> {
            ControlSignal payload = ControlSignal.forSwarm(signal, swarmId, currentCorrelation, idempotencyKey);
            rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, "sig." + signal + "." + swarmId, payload);
        });
    }

    private String currentCorrelation;

    private ResponseEntity<ControlResponse> idempotentSend(String signal, String swarmId, String idempotencyKey,
                                                           long timeoutMs, Runnable action) {
        return idempotency.findCorrelation(swarmId, signal, idempotencyKey)
            .map(corr -> {
                ControlResponse resp = new ControlResponse(corr, idempotencyKey,
                    new Watch("ev.ready." + signal + "." + swarmId, "ev.error." + signal + "." + swarmId), timeoutMs);
                return ResponseEntity.accepted().body(resp);
            })
            .orElseGet(() -> {
                currentCorrelation = UUID.randomUUID().toString();
                action.run();
                idempotency.record(swarmId, signal, idempotencyKey, currentCorrelation);
                ControlResponse resp = new ControlResponse(currentCorrelation, idempotencyKey,
                    new Watch("ev.ready." + signal + "." + swarmId, "ev.error." + signal + "." + swarmId), timeoutMs);
                return ResponseEntity.accepted().body(resp);
            });
    }

    public record ControlRequest(String idempotencyKey, String notes) {}
    public record Watch(String successTopic, String errorTopic) {}
    public record ControlResponse(String correlationId, String idempotencyKey, Watch watch, long timeoutMs) {}
}
