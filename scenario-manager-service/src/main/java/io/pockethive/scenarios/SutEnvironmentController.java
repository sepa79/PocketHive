package io.pockethive.scenarios;

import java.util.List;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes read‑only access to SUT environments for UI and orchestration.
 */
@RestController
@RequestMapping("/sut-environments")
public class SutEnvironmentController {

    private final SutEnvironmentService service;

    public SutEnvironmentController(SutEnvironmentService service) {
        this.service = service;
    }

    @GetMapping
    public List<SutEnvironment> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public ResponseEntity<SutEnvironment> get(@PathVariable("id") String id) {
        return Optional.ofNullable(service.find(id))
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
