package io.pockethive.orchestrator.runtime;

import io.pockethive.orchestrator.runtime.RuntimeCleanupContracts.ExecuteRequest;
import io.pockethive.orchestrator.runtime.RuntimeCleanupContracts.ExecuteResponse;
import io.pockethive.orchestrator.runtime.RuntimeCleanupContracts.Plan;
import io.pockethive.orchestrator.runtime.RuntimeCleanupContracts.PlanRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/runtime/cleanup")
public class RuntimeCleanupController {
    private final RuntimeReconciliationService service;

    public RuntimeCleanupController(RuntimeReconciliationService service) {
        this.service = service;
    }

    @PostMapping("/plan")
    public ResponseEntity<Plan> plan(@RequestBody PlanRequest request) {
        return ResponseEntity.ok(service.plan(request));
    }

    @PostMapping("/execute")
    public ResponseEntity<ExecuteResponse> execute(@RequestBody ExecuteRequest request) {
        return ResponseEntity.ok(service.execute(request));
    }

    @ExceptionHandler(RuntimeCleanupException.class)
    public ResponseEntity<Map<String, String>> cleanupError(RuntimeCleanupException ex) {
        return ResponseEntity.status(ex.status()).body(Map.of("message", ex.getMessage()));
    }
}
