package io.pockethive.scenarios;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/scenarios")
public class ScenarioController {
    private static final Logger log = LoggerFactory.getLogger(ScenarioController.class);
    private static final ObjectMapper LOG_MAPPER = new ObjectMapper();
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private final ScenarioService service;
    private final AvailableScenarioRegistry availableScenarios;

    public ScenarioController(ScenarioService service, AvailableScenarioRegistry availableScenarios) {
        this.service = service;
        this.availableScenarios = availableScenarios;
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
    public List<ScenarioSummary> list(@RequestParam(name = "includeDefunct", defaultValue = "false") boolean includeDefunct) {
        log.info("[REST] GET /scenarios includeDefunct={}", includeDefunct);
        List<ScenarioSummary> summaries = includeDefunct
                ? service.listAllSummaries()
                : availableScenarios.list();
        log.info("[REST] GET /scenarios -> {} items body={}", summaries.size(), safeJson(summaries));
        return summaries;
    }

    @GetMapping(value = "/defunct", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ScenarioSummary> defunct() {
        log.info("[REST] GET /scenarios/defunct");
        List<ScenarioSummary> summaries = service.listDefunctSummaries();
        log.info("[REST] GET /scenarios/defunct -> {} items body={}", summaries.size(), safeJson(summaries));
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

    @PostMapping("/reload")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reload() throws IOException {
        log.info("[REST] POST /scenarios/reload");
        service.reload();
        log.info("[REST] POST /scenarios/reload -> status=204");
    }

    @GetMapping(value = "/{id}/raw", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getRaw(@PathVariable("id") String id) throws IOException {
        log.info("[REST] GET /scenarios/{}/raw", id);
        if (service.find(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        try {
            String text = service.readScenarioRaw(id);
            log.info("[REST] GET /scenarios/{}/raw -> status=200 ({} chars)", id, text.length());
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(text);
        } catch (IllegalArgumentException e) {
            log.warn("[REST] GET /scenarios/{}/raw -> status=404 {}", id, e.getMessage());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
    }

    @PutMapping(value = "/{id}/raw", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<?> putRaw(@PathVariable("id") String id, @RequestBody String body) {
        log.info("[REST] PUT /scenarios/{}/raw ({} chars)", id, body != null ? body.length() : 0);
        try {
            service.updateScenarioFromRaw(id, body);
            log.info("[REST] PUT /scenarios/{}/raw -> status=204", id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException | IOException e) {
            log.warn("[REST] PUT /scenarios/{}/raw -> status=400 {}", id, e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PutMapping(
            value = "/{id}/plan",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Scenario updatePlan(@PathVariable("id") String id,
                               @RequestBody(required = false) Map<String, Object> plan) throws IOException {
        log.info("[REST] PUT /scenarios/{}/plan body={}", id, safeJson(plan));
        Scenario updated = service.updatePlan(id, plan != null ? plan : Map.of());
        log.info("[REST] PUT /scenarios/{}/plan -> status=200 body={}", id, safeJson(updated));
        return updated;
    }

    @GetMapping(value = "/{id}/bundle", produces = "application/zip")
    public ResponseEntity<byte[]> downloadBundle(@PathVariable("id") String id) throws IOException {
        log.info("[REST] GET /scenarios/{}/bundle", id);
        Scenario scenario = service.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Path bundleDir = service.bundleDir(scenario.getId());
        if (!Files.isDirectory(bundleDir)) {
            log.warn("Bundle directory {} for scenario '{}' not found", bundleDir, id);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Scenario bundle not found");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out);
             Stream<Path> paths = Files.walk(bundleDir)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                if (Files.isDirectory(path)) {
                    continue;
                }
                Path relative = bundleDir.relativize(path);
                String entryName = relative.toString().replace('\\', '/');
                zip.putNextEntry(new ZipEntry(entryName));
                Files.copy(path, zip);
                zip.closeEntry();
            }
        }

        byte[] bytes = out.toByteArray();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentLength(bytes.length);
        String fileName = scenario.getId() + "-bundle.zip";
        headers.setContentDispositionFormData("attachment", fileName);

        log.info("[REST] GET /scenarios/{}/bundle -> status=200 size={} filename={}", id, bytes.length, fileName);
        return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
    }

    @PostMapping(
            value = "/bundles",
            consumes = "application/zip",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Scenario> uploadBundle(@RequestBody byte[] body) throws IOException {
        int size = body != null ? body.length : 0;
        log.info("[REST] POST /scenarios/bundles contentType=application/zip size={}", size);
        Scenario created = service.createBundleFromZip(body);
        log.info("[REST] POST /scenarios/bundles -> status=201 body={}", safeJson(created));
        return ResponseEntity.status(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(created);
    }

    @PutMapping(
            value = "/{id}/bundle",
            consumes = "application/zip",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Scenario replaceBundle(@PathVariable("id") String id,
                                  @RequestBody byte[] body) throws IOException {
        int size = body != null ? body.length : 0;
        log.info("[REST] PUT /scenarios/{}/bundle contentType=application/zip size={}", id, size);
        Scenario updated = service.replaceBundleFromZip(id, body);
        log.info("[REST] PUT /scenarios/{}/bundle -> status=200 body={}", id, safeJson(updated));
        return updated;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    void invalidId() {
    }

    @PostMapping(value = "/{id}/runtime", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ScenarioRuntimeResponse> prepareRuntime(@PathVariable("id") String id,
                                                                  @RequestBody RuntimeRequest request) throws IOException {
        String swarmId = request != null ? request.swarmId() : null;
        log.info("[REST] POST /scenarios/{}/runtime swarmId={}", id, swarmId);
        Scenario scenario = service.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Path runtimeDir = service.prepareRuntimeDirectory(scenario.getId(), swarmId);
        ScenarioRuntimeResponse body = new ScenarioRuntimeResponse(scenario.getId(), swarmId, runtimeDir.toString());
        log.info("[REST] POST /scenarios/{}/runtime -> status=200 body={}", id, safeJson(body));
        return ResponseEntity.ok(body);
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

    public record RuntimeRequest(String swarmId) {
    }

    public record ScenarioRuntimeResponse(String scenarioId, String swarmId, String runtimeDir) {
    }
}
