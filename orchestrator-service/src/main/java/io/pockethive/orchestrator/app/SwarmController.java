package io.pockethive.orchestrator.app;

import io.pockethive.Topology;
import io.pockethive.orchestrator.domain.ControlSignal;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmCreateTracker;
import io.pockethive.orchestrator.domain.SwarmCreateTracker.Pending;
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

    public SwarmController(AmqpTemplate rabbit,
                           ContainerLifecycleManager lifecycle,
                           SwarmCreateTracker creates) {
        this.rabbit = rabbit;
        this.lifecycle = lifecycle;
        this.creates = creates;
    }

    @PostMapping("/{swarmId}/create")
    public ResponseEntity<ControlResponse> create(@PathVariable String swarmId, @RequestBody ControlRequest req) {
        String correlationId = UUID.randomUUID().toString();
        String instanceId = UUID.randomUUID().toString();
        Swarm swarm = lifecycle.startSwarm(swarmId, instanceId);
        creates.register(swarm.getInstanceId(), new Pending(swarmId, correlationId, req.idempotencyKey()));
        ControlResponse resp = new ControlResponse(correlationId, req.idempotencyKey(),
            new Watch("ev.ready.swarm-create." + swarmId, "ev.error.swarm-create." + swarmId), 120_000L);
        return ResponseEntity.accepted().body(resp);
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
        String correlationId = UUID.randomUUID().toString();
        ControlSignal payload = ControlSignal.forSwarm(signal, swarmId, idempotencyKey, correlationId);
        rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, "sig." + signal + "." + swarmId, payload);
        ControlResponse resp = new ControlResponse(correlationId, idempotencyKey,
            new Watch("ev.ready." + signal + "." + swarmId, "ev.error." + signal + "." + swarmId), timeoutMs);
        return ResponseEntity.accepted().body(resp);
    }

    public record ControlRequest(String idempotencyKey, String notes) {}
    public record Watch(String successTopic, String errorTopic) {}
    public record ControlResponse(String correlationId, String idempotencyKey, Watch watch, long timeoutMs) {}
}
