package io.pockethive.scenarios;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/scenarios")
public class ScenarioController {
    private static final Logger log = LoggerFactory.getLogger(ScenarioController.class);
    private final ScenarioService service;

    public ScenarioController(ScenarioService service) {
        this.service = service;
    }

    @PostMapping(consumes = {MediaType.APPLICATION_JSON_VALUE, "application/x-yaml", "application/yaml"})
    public ResponseEntity<Scenario> create(@Valid @RequestBody Scenario scenario,
                                           @RequestHeader(HttpHeaders.CONTENT_TYPE) String contentType) throws IOException {
        log.info("Creating scenario {}", scenario.getId());
        Scenario created = service.create(scenario, ScenarioService.formatFrom(contentType));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public List<Scenario> all() {
        log.info("Listing scenarios");
        return service.findAll();
    }

    @GetMapping("/{id}")
    public Scenario one(@PathVariable String id) {
        log.info("Fetching scenario {}", id);
        return service.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PutMapping(value = "/{id}", consumes = {MediaType.APPLICATION_JSON_VALUE, "application/x-yaml", "application/yaml"})
    public Scenario update(@PathVariable String id,
                           @Valid @RequestBody Scenario scenario,
                           @RequestHeader(HttpHeaders.CONTENT_TYPE) String contentType) throws IOException {
        log.info("Updating scenario {}", id);
        return service.update(id, scenario, ScenarioService.formatFrom(contentType));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) throws IOException {
        log.info("Deleting scenario {}", id);
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    void invalidId() {
    }
}
