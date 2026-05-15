package io.pockethive.scenarios;

import io.pockethive.auth.contract.AuthenticatedUserDto;
import io.pockethive.scenarios.auth.ScenarioManagerAuthorization;
import io.pockethive.scenarios.auth.ScenarioManagerCurrentUserHolder;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Exposes read‑only access to SUT environments for UI and orchestration.
 */
@RestController
@RequestMapping("/sut-environments")
public class SutEnvironmentController {

    private final SutEnvironmentService service;
    private final ScenarioManagerAuthorization authorization;

    public SutEnvironmentController(SutEnvironmentService service, ScenarioManagerAuthorization authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<SutEnvironment> list() {
        requireReadPocketHive();
        return service.list();
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SutEnvironment> get(@PathVariable("id") String id) {
        requireReadPocketHive();
        return Optional.ofNullable(service.find(id))
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/raw", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getRaw() throws IOException {
        requireReadPocketHive();
        String yaml = service.readRaw();
        return ResponseEntity.ok(yaml);
    }

    @PutMapping(value = "/raw", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<?> putRaw(@RequestBody String body) {
        requireManageDeployment();
        try {
            service.updateFromRaw(body);
            return ResponseEntity.noContent().build();
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    private void requireReadPocketHive() {
        AuthenticatedUserDto user = ScenarioManagerCurrentUserHolder.get();
        if (!authorization.canReadPocketHive(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, authorization.readDeniedMessage());
        }
    }

    private void requireManageDeployment() {
        AuthenticatedUserDto user = ScenarioManagerCurrentUserHolder.get();
        if (!authorization.canManageDeployment(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, authorization.manageDeniedMessage());
        }
    }
}
