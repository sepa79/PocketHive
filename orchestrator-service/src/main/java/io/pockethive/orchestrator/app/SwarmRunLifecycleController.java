package io.pockethive.orchestrator.app;

import io.pockethive.orchestrator.auth.OrchestratorCurrentUserHolder;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.orchestrator.domain.SwarmOperationCoordinator;
import io.pockethive.swarm.model.lifecycle.ControlRequest;
import io.pockethive.swarm.model.lifecycle.ControlResponse;
import io.pockethive.swarm.model.lifecycle.OperationType;
import io.pockethive.swarm.model.lifecycle.SwarmOperation;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Responsibility: map exact-run lifecycle HTTP requests to canonical command and operation owners.
 * Must not: decide permissions, publish signals or infer operation completion.
 * Contract: RESP-SWARM-COMMAND-ADMISSION — docs/architecture/runtime-responsibilities.md#resp-swarm-command-admission.
 */
@RestController
public final class SwarmRunLifecycleController {
    private final SwarmStore swarms;
    private final SwarmAccessService access;
    private final SwarmLifecycleCommandService commands;
    private final ControlResponseFactory responses;
    private final SwarmOperationCoordinator operations;

    public SwarmRunLifecycleController(SwarmStore swarms, SwarmAccessService access,
                                      SwarmLifecycleCommandService commands, ControlResponseFactory responses,
                                      SwarmOperationCoordinator operations) {
        this.swarms = swarms;
        this.access = access;
        this.commands = commands;
        this.responses = responses;
        this.operations = operations;
    }

    @GetMapping("/api/swarms/{swarmId}/runs/{runId}/operations/{correlationId}")
    public ResponseEntity<SwarmOperation> operation(@PathVariable String swarmId, @PathVariable String runId,
                                                  @PathVariable String correlationId) {
        var swarm = swarms.find(swarmId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!access.canRead(OrchestratorCurrentUserHolder.get(), swarm)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "PocketHive swarm read permission required");
        }
        return operations.findByCorrelation(correlationId)
            .filter(operation -> swarm.getRunId().equals(runId)
                && operation.swarmId().equals(swarmId) && operation.runtime().runId().equals(runId))
            .map(ResponseEntity::ok)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PostMapping("/api/swarms/{swarmId}/runs/{runId}/start")
    public ResponseEntity<ControlResponse> start(@PathVariable String swarmId, @PathVariable String runId,
                                               @RequestBody ControlRequest request) {
        return dispatch(swarmId, runId, request, OperationType.START, Duration.ofSeconds(180));
    }

    @PostMapping("/api/swarms/{swarmId}/runs/{runId}/stop")
    public ResponseEntity<ControlResponse> stop(@PathVariable String swarmId, @PathVariable String runId,
                                              @RequestBody ControlRequest request) {
        return dispatch(swarmId, runId, request, OperationType.STOP, Duration.ofSeconds(90));
    }

    private ResponseEntity<ControlResponse> dispatch(String swarmId, String runId, ControlRequest request,
                                                    OperationType type, Duration timeout) {
        var swarm = swarms.find(swarmId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var user = OrchestratorCurrentUserHolder.get();
        boolean allowed = type == OperationType.START ? access.canRun(user, swarm) : access.canManage(user, swarm);
        if (!allowed) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "PocketHive swarm permission required");
        var reservation = commands.dispatchForRun(type, swarmId, runId, request.idempotencyKey(), timeout);
        return ResponseEntity.accepted().body(responses.create(reservation.operation(), timeout));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> conflict(IllegalStateException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", error.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }
}
