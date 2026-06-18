package io.pockethive.orchestrator.runtime;

import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.Capabilities;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/runtime/debug")
public class RuntimeDebugController {
    @GetMapping("/capabilities")
    public ResponseEntity<Capabilities> capabilities() {
        return ResponseEntity.ok(Capabilities.current());
    }
}
