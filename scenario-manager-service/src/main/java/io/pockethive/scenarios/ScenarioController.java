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
import io.pockethive.swarm.model.SutEnvironment;

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
        log.info("[REST] POST /scenarios contentType={} scenario={}", contentType, safeJson(scenarioSummary(scenario)));
        Scenario created = service.create(scenario, ScenarioService.formatFrom(contentType));
        log.info("[REST] POST /scenarios -> status=201 scenario={}", safeJson(scenarioSummary(created)));
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

    @GetMapping(value = "/folders", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<String> listBundleFolders() throws IOException {
        log.info("[REST] GET /scenarios/folders");
        List<String> folders = service.listBundleFolders();
        log.info("[REST] GET /scenarios/folders -> {} items body={}", folders.size(), safeJson(folders));
        return folders;
    }

    @PostMapping(value = "/folders", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> createBundleFolder(@RequestBody FolderRequest request) throws IOException {
        String path = request != null ? request.path() : null;
        log.info("[REST] POST /scenarios/folders path={}", path);
        try {
            service.createBundleFolder(path);
            log.info("[REST] POST /scenarios/folders -> status=204");
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            log.warn("[REST] POST /scenarios/folders -> status=400 {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @DeleteMapping(value = "/folders")
    public ResponseEntity<Void> deleteBundleFolder(@RequestParam(name = "path") String path) throws IOException {
        log.info("[REST] DELETE /scenarios/folders path={}", path);
        try {
            service.deleteBundleFolder(path);
            log.info("[REST] DELETE /scenarios/folders -> status=204");
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            log.warn("[REST] DELETE /scenarios/folders -> status=400 {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @PostMapping(value = "/{id}/move", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> moveScenarioToFolder(@PathVariable("id") String id,
                                                     @RequestBody FolderRequest request) throws IOException {
        String path = request != null ? request.path() : null;
        log.info("[REST] POST /scenarios/{}/move path={}", id, path);
        if (service.find(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        try {
            service.moveScenarioToFolder(id, path);
            log.info("[REST] POST /scenarios/{}/move -> status=204", id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            log.warn("[REST] POST /scenarios/{}/move -> status=400 {}", id, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Scenario one(@PathVariable("id") String id) {
        log.info("[REST] GET /scenarios/{}", id);
        Scenario scenario = service.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        log.info("[REST] GET /scenarios/{} -> status=200 scenario={}", id, safeJson(scenarioSummary(scenario)));
        return scenario;
    }

    @PutMapping(
            value = "/{id}",
            consumes = {MediaType.APPLICATION_JSON_VALUE, "application/x-yaml", "application/yaml"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Scenario update(@PathVariable("id") String id,
                           @Valid @RequestBody Scenario scenario,
                           @RequestHeader(HttpHeaders.CONTENT_TYPE) String contentType) throws IOException {
        log.info("[REST] PUT /scenarios/{} contentType={} scenario={}", id, contentType, safeJson(scenarioSummary(scenario)));
        Scenario updated = service.update(id, scenario, ScenarioService.formatFrom(contentType));
        log.info("[REST] PUT /scenarios/{} -> status=200 scenario={}", id, safeJson(scenarioSummary(updated)));
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

    @GetMapping(value = "/{id}/variables", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getVariables(@PathVariable("id") String id,
                                               @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
                                               @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) throws IOException {
        log.info("[REST] GET /scenarios/{}/variables correlationId={} idempotencyKey={}", id, correlationId, idempotencyKey);
        if (service.find(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        String text = service.readVariablesRaw(id);
        if (text == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "variables.yaml not found");
        }
        log.info("[REST] GET /scenarios/{}/variables -> status=200 ({} chars)", id, text.length());
        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_PLAIN)
            .body(text);
    }

    @PutMapping(value = "/{id}/variables", consumes = MediaType.TEXT_PLAIN_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<VariablesWriteResponse> putVariables(@PathVariable("id") String id,
                                                               @RequestBody String body,
                                                               @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
                                                               @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) throws IOException {
        int size = body != null ? body.length() : 0;
        log.info("[REST] PUT /scenarios/{}/variables ({} chars) correlationId={} idempotencyKey={}", id, size, correlationId, idempotencyKey);
        if (service.find(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        try {
            ScenarioService.VariablesValidationResult result = service.writeVariables(id, body != null ? body : "");
            VariablesWriteResponse resp = new VariablesWriteResponse("ok", result.warnings());
            log.info("[REST] PUT /scenarios/{}/variables -> status=200 warnings={}", id, resp.warnings().size());
            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(resp);
        } catch (IllegalArgumentException e) {
            log.warn("[REST] PUT /scenarios/{}/variables -> status=400 {}", id, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @GetMapping(value = "/{id}/variables/resolve", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<VariablesResolveResponse> resolveVariables(@PathVariable("id") String id,
                                                                     @RequestParam(name = "profileId", required = false) String profileId,
                                                                     @RequestParam(name = "sutId", required = false) String sutId,
                                                                     @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
                                                                     @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) throws IOException {
        log.info("[REST] GET /scenarios/{}/variables/resolve profileId={} sutId={} correlationId={} idempotencyKey={}",
            id, profileId, sutId, correlationId, idempotencyKey);
        if (service.find(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        try {
            ScenarioService.VariablesResolutionResult resolved = service.resolveVariables(id, profileId, sutId);
            VariablesResolveResponse resp = new VariablesResolveResponse(
                profileId,
                sutId,
                resolved.vars(),
                resolved.warnings());
            log.info("[REST] GET /scenarios/{}/variables/resolve -> status=200 vars={} warnings={}",
                id, resp.vars().size(), resp.warnings().size());
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException e) {
            log.warn("[REST] GET /scenarios/{}/variables/resolve -> status=400 {}", id, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @GetMapping(value = "/{id}/suts", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<String> listBundleSuts(@PathVariable("id") String id) throws IOException {
        log.info("[REST] GET /scenarios/{}/suts", id);
        if (service.find(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        List<String> ids = service.listSutIds(id);
        log.info("[REST] GET /scenarios/{}/suts -> status=200 {} items", id, ids.size());
        return ids;
    }

    @GetMapping(value = "/{id}/suts/{sutId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public SutEnvironment getBundleSut(@PathVariable("id") String id,
                                       @PathVariable("sutId") String sutId,
                                       @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
                                       @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) throws IOException {
        log.info("[REST] GET /scenarios/{}/suts/{} correlationId={} idempotencyKey={}", id, sutId, correlationId, idempotencyKey);
        if (service.find(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        try {
            SutEnvironment env = service.readBundleSut(id, sutId);
            log.info("[REST] GET /scenarios/{}/suts/{} -> status=200", id, sutId);
            return env;
        } catch (IllegalArgumentException e) {
            log.warn("[REST] GET /scenarios/{}/suts/{} -> status=404 {}", id, sutId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
    }

    @GetMapping(value = "/{id}/suts/{sutId}/raw", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getBundleSutRaw(@PathVariable("id") String id,
                                                  @PathVariable("sutId") String sutId,
                                                  @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
                                                  @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) throws IOException {
        log.info("[REST] GET /scenarios/{}/suts/{}/raw correlationId={} idempotencyKey={}", id, sutId, correlationId, idempotencyKey);
        if (service.find(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        String text = service.readBundleSutRaw(id, sutId);
        if (text == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "sut.yaml not found");
        }
        log.info("[REST] GET /scenarios/{}/suts/{}/raw -> status=200 ({} chars)", id, sutId, text.length());
        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_PLAIN)
            .body(text);
    }

    @PutMapping(value = "/{id}/suts/{sutId}/raw", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<Void> putBundleSutRaw(@PathVariable("id") String id,
                                                @PathVariable("sutId") String sutId,
                                                @RequestBody String body,
                                                @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
                                                @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) throws IOException {
        int size = body != null ? body.length() : 0;
        log.info("[REST] PUT /scenarios/{}/suts/{}/raw ({} chars) correlationId={} idempotencyKey={}",
            id, sutId, size, correlationId, idempotencyKey);
        if (service.find(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        try {
            service.writeBundleSutRaw(id, sutId, body != null ? body : "");
            log.info("[REST] PUT /scenarios/{}/suts/{}/raw -> status=204", id, sutId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            log.warn("[REST] PUT /scenarios/{}/suts/{}/raw -> status=400 {}", id, sutId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @DeleteMapping(value = "/{id}/suts/{sutId}")
    public ResponseEntity<Void> deleteBundleSut(@PathVariable("id") String id,
                                                @PathVariable("sutId") String sutId,
                                                @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
                                                @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) throws IOException {
        log.info("[REST] DELETE /scenarios/{}/suts/{} correlationId={} idempotencyKey={}", id, sutId, correlationId, idempotencyKey);
        if (service.find(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        try {
            service.deleteBundleSut(id, sutId);
            log.info("[REST] DELETE /scenarios/{}/suts/{} -> status=204", id, sutId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            log.warn("[REST] DELETE /scenarios/{}/suts/{} -> status=404 {}", id, sutId, e.getMessage());
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

    @GetMapping(value = "/{id}/schemas", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<String> listSchemas(@PathVariable("id") String id) throws IOException {
        log.info("[REST] GET /scenarios/{}/schemas", id);
        List<String> files = service.listSchemaFiles(id);
        log.info("[REST] GET /scenarios/{}/schemas -> status=200 body={}", id, safeJson(files));
        return files;
    }

    @GetMapping(value = "/{id}/schema", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> readSchema(@PathVariable("id") String id,
                                             @RequestParam("path") String path) throws IOException {
        log.info("[REST] GET /scenarios/{}/schema path={}", id, path);
        String text = service.readBundleFile(id, path);
        log.info("[REST] GET /scenarios/{}/schema -> status=200 ({} chars)", id, text != null ? text.length() : 0);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(text);
    }

    @PutMapping(value = "/{id}/schema", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> writeSchema(@PathVariable("id") String id,
                                            @RequestParam("path") String path,
                                            @RequestBody String body) throws IOException {
        int size = body != null ? body.length() : 0;
        log.info("[REST] PUT /scenarios/{}/schema path={} ({} chars)", id, path, size);
        service.writeSchemaFile(id, path, body != null ? body : "");
        log.info("[REST] PUT /scenarios/{}/schema -> status=204", id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/{id}/http-templates", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<String> listHttpTemplates(@PathVariable("id") String id) throws IOException {
        log.info("[REST] GET /scenarios/{}/http-templates", id);
        List<String> files = service.listHttpTemplateFiles(id);
        log.info("[REST] GET /scenarios/{}/http-templates -> status=200 body={}", id, safeJson(files));
        return files;
    }

    @GetMapping(value = "/{id}/http-template", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> readHttpTemplate(@PathVariable("id") String id,
                                                   @RequestParam("path") String path) throws IOException {
        log.info("[REST] GET /scenarios/{}/http-template path={}", id, path);
        String text = service.readBundleFile(id, path);
        log.info("[REST] GET /scenarios/{}/http-template -> status=200 ({} chars)", id, text != null ? text.length() : 0);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(text);
    }

    @PutMapping(value = "/{id}/http-template", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<Void> writeHttpTemplate(@PathVariable("id") String id,
                                                  @RequestParam("path") String path,
                                                  @RequestBody String body) throws IOException {
        int size = body != null ? body.length() : 0;
        log.info("[REST] PUT /scenarios/{}/http-template path={} ({} chars)", id, path, size);
        service.writeHttpTemplate(id, path, body != null ? body : "");
        log.info("[REST] PUT /scenarios/{}/http-template -> status=204", id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/{id}/http-template/rename")
    public ResponseEntity<Void> renameHttpTemplate(@PathVariable("id") String id,
                                                   @RequestParam("from") String fromPath,
                                                   @RequestParam("to") String toPath) throws IOException {
        log.info("[REST] POST /scenarios/{}/http-template/rename from={} to={}", id, fromPath, toPath);
        service.renameHttpTemplate(id, fromPath, toPath);
        log.info("[REST] POST /scenarios/{}/http-template/rename -> status=204", id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping(value = "/{id}/http-template")
    public ResponseEntity<Void> deleteHttpTemplate(@PathVariable("id") String id,
                                                   @RequestParam("path") String path) throws IOException {
        log.info("[REST] DELETE /scenarios/{}/http-template path={}", id, path);
        service.deleteHttpTemplate(id, path);
        log.info("[REST] DELETE /scenarios/{}/http-template -> status=204", id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/{id}/bundle", produces = "application/zip")
    public ResponseEntity<byte[]> downloadBundle(@PathVariable("id") String id) throws IOException {
        log.info("[REST] GET /scenarios/{}/bundle", id);
        Scenario scenario = service.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Path bundleDir;
        try {
            bundleDir = service.bundleDirFor(scenario.getId());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Scenario bundle not found", e);
        }
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

    private static Map<String, Object> scenarioSummary(Scenario scenario) {
        if (scenario == null) {
            return Map.of();
        }
        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("id", scenario.getId());
        summary.put("name", scenario.getName());
        summary.put("description", scenario.getDescription());
        return summary;
    }

    public record RuntimeRequest(String swarmId) {
    }

    public record ScenarioRuntimeResponse(String scenarioId, String swarmId, String runtimeDir) {
    }

    public record VariablesWriteResponse(String status, List<String> warnings) {
    }

    public record VariablesResolveResponse(String profileId, String sutId, Map<String, Object> vars, List<String> warnings) {
    }

    public record FolderRequest(String path) {
    }
}
