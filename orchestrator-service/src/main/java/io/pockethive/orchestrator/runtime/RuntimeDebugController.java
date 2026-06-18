package io.pockethive.orchestrator.runtime;

import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.Capabilities;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.ResourceListRequest;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.ResourceListResponse;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.RuntimeInspectResponse;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.RuntimeLogsRequest;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.RuntimeLogsResponse;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.RuntimeTargetRequest;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.RuntimeVersionResponse;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/runtime/debug")
public class RuntimeDebugController {
    private final RuntimeDebugService service;

    public RuntimeDebugController(RuntimeDebugService service) {
        this.service = service;
    }

    @GetMapping("/capabilities")
    public ResponseEntity<Capabilities> capabilities() {
        return ResponseEntity.ok(Capabilities.current());
    }

    @PostMapping("/resources/list")
    public ResponseEntity<ResourceListResponse> listResources(@RequestBody ResourceListRequest request) {
        return ResponseEntity.ok(service.list(request));
    }

    @PostMapping("/resources/logs")
    public ResponseEntity<RuntimeLogsResponse> logs(@RequestBody RuntimeLogsRequest request) {
        return ResponseEntity.ok(service.logs(request));
    }

    @PostMapping("/resources/version")
    public ResponseEntity<RuntimeVersionResponse> version(@RequestBody RuntimeTargetRequest request) {
        return ResponseEntity.ok(service.version(request));
    }

    @PostMapping("/resources/inspect")
    public ResponseEntity<RuntimeInspectResponse> inspect(@RequestBody RuntimeTargetRequest request) {
        return ResponseEntity.ok(service.inspect(request));
    }

    @ExceptionHandler(RuntimeDebugException.class)
    public ResponseEntity<Map<String, String>> debugError(RuntimeDebugException ex) {
        return ResponseEntity.status(ex.status()).body(Map.of("error", ex.getMessage()));
    }
}
