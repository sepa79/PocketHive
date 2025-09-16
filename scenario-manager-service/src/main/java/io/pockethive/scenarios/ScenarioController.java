package io.pockethive.scenarios;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    private static final ObjectMapper LOG_MAPPER = new ObjectMapper();
    private final ScenarioService service;

    public ScenarioController(ScenarioService service) {
        this.service = service;
    }

    @PostMapping(
            consumes = {MediaType.APPLICATION_JSON_VALUE, "application/x-yaml", "application/yaml"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Scenario> create(@Valid @RequestBody Scenario scenario,
                                           @RequestHeader(HttpHeaders.CONTENT_TYPE) String contentType) throws IOException {
        log.info("[REST] POST /scenarios contentType={} body={}", contentType, safeJson(scenario));
        Scenario created = service.create(scenario, ScenarioService.formatFrom(contentType));
        log.info("[REST] POST /scenarios -> status=201 body={}", safeJson(created));
        return ResponseEntity.status(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(created);
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ScenarioSummary> list() {
        log.info("[REST] GET /scenarios");
        List<ScenarioSummary> summaries = service.list();
        log.info("[REST] GET /scenarios -> {} items body={}", summaries.size(), safeJson(summaries));
        return summaries;
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Scenario one(@PathVariable("id") String id) {
        log.info("[REST] GET /scenarios/{}", id);
        Scenario scenario = service.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        log.info("[REST] GET /scenarios/{} -> status=200 body={}", id, safeJson(scenario));
        return scenario;
    }

    @PutMapping(
            value = "/{id}",
            consumes = {MediaType.APPLICATION_JSON_VALUE, "application/x-yaml", "application/yaml"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Scenario update(@PathVariable("id") String id,
                           @Valid @RequestBody Scenario scenario,
                           @RequestHeader(HttpHeaders.CONTENT_TYPE) String contentType) throws IOException {
        log.info("[REST] PUT /scenarios/{} contentType={} body={}", id, contentType, safeJson(scenario));
        Scenario updated = service.update(id, scenario, ScenarioService.formatFrom(contentType));
        log.info("[REST] PUT /scenarios/{} -> status=200 body={}", id, safeJson(updated));
        return updated;
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") String id) throws IOException {
        log.info("[REST] DELETE /scenarios/{}", id);
        service.delete(id);
        log.info("[REST] DELETE /scenarios/{} -> status=204", id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    void invalidId() {
    }

    private static String safeJson(Object value) {
        if (value == null) {
            return "";
        }
        try {
            String json = LOG_MAPPER.writeValueAsString(value);
            if (json.length() > 500) {
                return json.substring(0, 500) + "…";
            }
            return json;
        } catch (Exception e) {
            String text = value.toString();
            if (text.length() > 500) {
                return text.substring(0, 500) + "…";
            }
            return text;
        }
    }
}
