package io.pockethive.orchestrator.planner;

import com.fasterxml.jackson.databind.JsonNode;
import io.pockethive.journal.api.SwarmRunSummary;
import io.pockethive.orchestrator.auth.OrchestratorEndpointAuthorization;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Responsibility: authorize and map Planner catalogue and exact-run association HTTP requests.
 * Must not: resolve selections, perform SQL or settle lifecycle operations.
 * Contract: RESP-POCKETPLANNER-INTEGRATION — docs/architecture/runtime-responsibilities.md#resp-pocketplanner-integration.
 */
@RestController
@RequestMapping("/api/planner")
public final class PlannerController {
    private final OrchestratorEndpointAuthorization authorization;
    private final PocketPlannerClient planner;
    private final PlannerRunService runs;

    public PlannerController(OrchestratorEndpointAuthorization authorization, PocketPlannerClient planner, PlannerRunService runs) {
        this.authorization = authorization;
        this.planner = planner;
        this.runs = runs;
    }

    @GetMapping("/catalogue")
    public JsonNode catalogue(@RequestHeader(value = "Authorization", required = false) String token) {
        authorization.requireReadDeployment();
        return planner.catalogue(token);
    }

    @PostMapping("/validate")
    public void validate(@Valid @RequestBody PlannerSelection selection,
                         @RequestHeader(value = "Authorization", required = false) String token) {
        authorization.requireManageDeployment();
        runs.validate(selection, token);
    }

    @GetMapping("/runs/{swarmId}/{runId}")
    public SwarmRunSummary get(@PathVariable String swarmId, @PathVariable String runId) {
        authorization.requireReadDeployment();
        return runs.get(swarmId, runId);
    }

    @GetMapping("/swarms/{swarmId}")
    public SwarmRunSummary current(@PathVariable String swarmId) {
        authorization.requireReadSwarm(swarmId);
        return runs.current(swarmId);
    }

    @PostMapping("/runs/{swarmId}/{runId}")
    public SwarmRunSummary assign(@PathVariable String swarmId, @PathVariable String runId,
                                 @Valid @RequestBody PlannerSelection selection,
                                 @RequestHeader(value = "Authorization", required = false) String token) {
        authorization.requireManageDeployment();
        return runs.assign(swarmId, runId, selection, token);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> error(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).body(Map.of("message", String.valueOf(exception.getReason())));
    }
}
