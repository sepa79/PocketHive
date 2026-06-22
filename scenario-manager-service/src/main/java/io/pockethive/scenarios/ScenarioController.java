package io.pockethive.scenarios;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.pockethive.auth.contract.AuthenticatedUserDto;
import io.pockethive.scenarios.auth.ScenarioManagerAuthorization;
import io.pockethive.scenarios.auth.ScenarioManagerCurrentUserHolder;
import io.pockethive.scenarios.validation.BundleValidationException;
import io.pockethive.scenarios.validation.BundleValidationResult;
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
    private final ScenarioManagerAuthorization authorization;

    public ScenarioController(ScenarioService service,
                              AvailableScenarioRegistry availableScenarios,
                              ScenarioManagerAuthorization authorization) {
        this.service = service;
        this.availableScenarios = availableScenarios;
        this.authorization = authorization;
    }

    @PostMapping(
            consumes = {MediaType.APPLICATION_JSON_VALUE, "application/x-yaml", "application/yaml"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Scenario> create(@Valid @RequestBody Scenario scenario,
                                           @RequestHeader(HttpHeaders.CONTENT_TYPE) String contentType) throws IOException {
        log.info("[REST] POST /scenarios contentType={} scenario={}", contentType, safeJson(scenarioSummary(scenario)));
        requireManageAllFolders();
        Scenario created = service.create(scenario, ScenarioService.formatFrom(contentType));
        log.info("[REST] POST /scenarios -> status=201 scenario={}", safeJson(scenarioSummary(created)));
        return ResponseEntity.status(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(created);
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ScenarioSummary> list(@RequestParam(name = "includeDefunct", defaultValue = "false") boolean includeDefunct) {
        log.info("[REST] GET /scenarios includeDefunct={}", includeDefunct);
        AuthenticatedUserDto user = currentUser();
        List<ScenarioSummary> summaries = includeDefunct
                ? service.listAllSummaries()
                : availableScenarios.list();
        summaries = summaries.stream()
                .filter(summary -> canRead(user, summary.id()))
                .toList();
        log.info("[REST] GET /scenarios -> {} items body={}", summaries.size(), safeJson(summaries));
        return summaries;
    }

    @GetMapping(value = "/defunct", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ScenarioSummary> defunct() {
        log.info("[REST] GET /scenarios/defunct");
        AuthenticatedUserDto user = currentUser();
        List<ScenarioSummary> summaries = service.listDefunctSummaries().stream()
                .filter(summary -> canRead(user, summary.id()))
                .toList();
        log.info("[REST] GET /scenarios/defunct -> {} items body={}", summaries.size(), safeJson(summaries));
        return summaries;
    }

    @GetMapping(value = "/bundles/workspaces", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ScenarioService.BundleTemplateSummary> listBundleWorkspaces() {
        log.info("[REST] GET /scenarios/bundles/workspaces");
        AuthenticatedUserDto user = currentUser();
        List<ScenarioService.BundleTemplateSummary> summaries = service.listBundleTemplates().stream()
                .filter(summary -> canReadBundleSummary(user, summary))
                .toList();
        log.info("[REST] GET /scenarios/bundles/workspaces -> {} items body={}", summaries.size(), safeJson(summaries));
        return summaries;
    }

    @GetMapping(value = "/bundles/tree", produces = MediaType.APPLICATION_JSON_VALUE)
    public ScenarioService.BundleTree readBundleTree(@RequestParam("bundleKey") String bundleKey) throws IOException {
        log.info("[REST] GET /scenarios/bundles/tree bundleKey={}", bundleKey);
        requireReadBundle(bundleKey);
        try {
            ScenarioService.BundleTree tree = service.readBundleTree(bundleKey);
            log.info("[REST] GET /scenarios/bundles/tree -> status=200 bundleKey={} nodes={}", bundleKey, tree.nodes().size());
            return tree;
        } catch (IllegalArgumentException e) {
            log.warn("[REST] GET /scenarios/bundles/tree -> status=400 bundleKey={} {}", bundleKey, e.getMessage());
            throw bundleReadException(e);
        }
    }

    @GetMapping(value = "/bundles/file", produces = MediaType.APPLICATION_JSON_VALUE)
    public ScenarioService.BundleFilePayload readBundleFile(@RequestParam("bundleKey") String bundleKey,
                                                            @RequestParam("path") String path) throws IOException {
        log.info("[REST] GET /scenarios/bundles/file bundleKey={} path={}", bundleKey, path);
        requireReadBundle(bundleKey);
        try {
            ScenarioService.BundleFilePayload file = service.readBundleWorkspaceFile(bundleKey, path);
            log.info("[REST] GET /scenarios/bundles/file -> status=200 bundleKey={} path={} editorKind={}",
                    bundleKey, file.path(), file.editorKind());
            return file;
        } catch (IllegalArgumentException e) {
            log.warn("[REST] GET /scenarios/bundles/file -> status=400 bundleKey={} path={} {}", bundleKey, path, e.getMessage());
            throw bundleReadException(e);
        }
    }

    @PutMapping(value = "/bundles/file", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ScenarioService.BundleFileWriteResult writeBundleFile(@RequestParam("bundleKey") String bundleKey,
                                                                 @RequestParam("path") String path,
                                                                 @RequestBody BundleFileWriteRequest request) throws IOException {
        log.info("[REST] PUT /scenarios/bundles/file bundleKey={} path={}", bundleKey, path);
        requireManageBundle(bundleKey);
        try {
            ScenarioService.BundleFileWriteResult result = service.writeBundleWorkspaceFile(
                    bundleKey,
                    path,
                    request != null ? request.content() : null,
                    request != null ? request.expectedRevision() : null);
            log.info("[REST] PUT /scenarios/bundles/file -> status=200 bundleKey={} path={}", bundleKey, path);
            return result;
        } catch (ScenarioService.WorkspaceConflictException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        } catch (ScenarioService.WorkspaceUnsupportedMediaTypeException e) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            log.warn("[REST] PUT /scenarios/bundles/file -> status=400 bundleKey={} path={} {}", bundleKey, path, e.getMessage());
            throw bundleReadException(e);
        }
    }

    @PostMapping(value = "/bundles/files", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ScenarioService.BundleFilePayload> createBundleFile(@RequestParam("bundleKey") String bundleKey,
                                                                              @RequestBody BundleFileCreateRequest request) throws IOException {
        String path = request != null ? request.path() : null;
        log.info("[REST] POST /scenarios/bundles/files bundleKey={} path={}", bundleKey, path);
        requireManageBundle(bundleKey);
        try {
            ScenarioService.BundleFilePayload file = service.createBundleWorkspaceFile(
                    bundleKey,
                    path,
                    request != null ? request.content() : null);
            log.info("[REST] POST /scenarios/bundles/files -> status=201 bundleKey={} path={}", bundleKey, file.path());
            return ResponseEntity.status(HttpStatus.CREATED).body(file);
        } catch (ScenarioService.WorkspaceConflictException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        } catch (ScenarioService.WorkspaceUnsupportedMediaTypeException e) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            log.warn("[REST] POST /scenarios/bundles/files -> status=400 bundleKey={} path={} {}", bundleKey, path, e.getMessage());
            throw bundleReadException(e);
        }
    }

    @PostMapping(value = "/bundles/folders", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> createBundleWorkspaceFolder(@RequestParam("bundleKey") String bundleKey,
                                                            @RequestBody FolderRequest request) throws IOException {
        String path = request != null ? request.path() : null;
        log.info("[REST] POST /scenarios/bundles/folders bundleKey={} path={}", bundleKey, path);
        requireManageBundle(bundleKey);
        try {
            service.createBundleWorkspaceFolder(bundleKey, path);
            log.info("[REST] POST /scenarios/bundles/folders -> status=204 bundleKey={} path={}", bundleKey, path);
            return ResponseEntity.noContent().build();
        } catch (ScenarioService.WorkspaceConflictException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            log.warn("[REST] POST /scenarios/bundles/folders -> status=400 bundleKey={} path={} {}", bundleKey, path, e.getMessage());
            throw bundleReadException(e);
        }
    }

    @PostMapping(value = "/bundles/entries/rename", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> renameBundleWorkspaceEntry(@RequestParam("bundleKey") String bundleKey,
                                                           @RequestBody BundleEntryRenameRequest request) throws IOException {
        String path = request != null ? request.path() : null;
        String name = request != null ? request.name() : null;
        log.info("[REST] POST /scenarios/bundles/entries/rename bundleKey={} path={} name={}", bundleKey, path, name);
        requireManageBundle(bundleKey);
        try {
            service.renameBundleWorkspaceEntry(bundleKey, path, name);
            log.info("[REST] POST /scenarios/bundles/entries/rename -> status=204 bundleKey={} path={} name={}", bundleKey, path, name);
            return ResponseEntity.noContent().build();
        } catch (ScenarioService.WorkspaceConflictException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            log.warn("[REST] POST /scenarios/bundles/entries/rename -> status=400 bundleKey={} path={} name={} {}", bundleKey, path, name, e.getMessage());
            throw bundleReadException(e);
        }
    }

    @DeleteMapping(value = "/bundles/entry")
    public ResponseEntity<Void> deleteBundleWorkspaceEntry(@RequestParam("bundleKey") String bundleKey,
                                                           @RequestParam("path") String path) throws IOException {
        log.info("[REST] DELETE /scenarios/bundles/entry bundleKey={} path={}", bundleKey, path);
        requireManageBundle(bundleKey);
        try {
            service.deleteBundleWorkspaceEntry(bundleKey, path);
            log.info("[REST] DELETE /scenarios/bundles/entry -> status=204 bundleKey={} path={}", bundleKey, path);
            return ResponseEntity.noContent().build();
        } catch (ScenarioService.WorkspaceConflictException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            log.warn("[REST] DELETE /scenarios/bundles/entry -> status=400 bundleKey={} path={} {}", bundleKey, path, e.getMessage());
            throw bundleReadException(e);
        }
    }

    @GetMapping(value = "/folders", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<String> listBundleFolders() throws IOException {
        log.info("[REST] GET /scenarios/folders");
        AuthenticatedUserDto user = currentUser();
        requireManagePocketHive();
        List<String> folders = service.listBundleFolders().stream()
                .filter(path -> canManageFolder(user, path))
                .toList();
        log.info("[REST] GET /scenarios/folders -> {} items body={}", folders.size(), safeJson(folders));
        return folders;
    }

    @PostMapping(value = "/folders", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> createBundleFolder(@RequestBody FolderRequest request) throws IOException {
        String path = request != null ? request.path() : null;
        log.info("[REST] POST /scenarios/folders path={}", path);
        requireManageFolder(path);
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
        requireManageFolder(path);
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
        requireManageScenario(id);
        requireManageFolder(path);
        try {
            service.moveScenarioToFolder(id, path);
            log.info("[REST] POST /scenarios/{}/move -> status=204", id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            log.warn("[REST] POST /scenarios/{}/move -> status=400 {}", id, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @PostMapping(value = "/bundles/move", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> moveBundleToFolder(@RequestBody BundleMoveRequest request) throws IOException {
        String bundleKey = request != null ? request.bundleKey() : null;
        String path = request != null ? request.path() : null;
        log.info("[REST] POST /scenarios/bundles/move bundleKey={} path={}", bundleKey, path);
        requireManageBundle(bundleKey);
        requireManageFolder(path);
        try {
            service.moveBundleToFolder(bundleKey, path);
            log.info("[REST] POST /scenarios/bundles/move -> status=204 bundleKey={}", bundleKey);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            log.warn("[REST] POST /scenarios/bundles/move -> status=400 bundleKey={} {}", bundleKey, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Scenario one(@PathVariable("id") String id) {
        // NOTE: This endpoint still resolves by raw scenario id via service.find(id).
        // That means direct callers can currently fetch defunct scenarios even though
        // UI create flows are expected to preflight against /api/templates first.
        log.info("[REST] GET /scenarios/{}", id);
        requireReadScenario(id);
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
        requireManageScenario(id);
        Scenario updated = service.update(id, scenario, ScenarioService.formatFrom(contentType));
        log.info("[REST] PUT /scenarios/{} -> status=200 scenario={}", id, safeJson(scenarioSummary(updated)));
        return updated;
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") String id) throws IOException {
        log.info("[REST] DELETE /scenarios/{}", id);
        requireManageScenario(id);
        service.delete(id);
        log.info("[REST] DELETE /scenarios/{} -> status=204", id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/bundles")
    public ResponseEntity<Void> deleteBundle(@RequestParam("bundleKey") String bundleKey) throws IOException {
        log.info("[REST] DELETE /scenarios/bundles bundleKey={}", bundleKey);
        requireManageBundle(bundleKey);
        try {
            service.deleteBundle(bundleKey);
            log.info("[REST] DELETE /scenarios/bundles -> status=204 bundleKey={}", bundleKey);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            log.warn("[REST] DELETE /scenarios/bundles -> status=400 bundleKey={} {}", bundleKey, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @PostMapping("/reload")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reload() throws IOException {
        log.info("[REST] POST /scenarios/reload");
        requireManageAllFolders();
        service.reload();
        log.info("[REST] POST /scenarios/reload -> status=204");
    }

    @GetMapping(value = "/{id}/raw", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getRaw(@PathVariable("id") String id) throws IOException {
        log.info("[REST] GET /scenarios/{}/raw", id);
        requireReadScenario(id);
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
        requireReadScenario(id);
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
        requireManageScenario(id);
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
        requireReadScenario(id);
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
        requireReadScenario(id);
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
        requireReadScenario(id);
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
        requireReadScenario(id);
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
        requireManageScenario(id);
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
        requireManageScenario(id);
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
        requireManageScenario(id);
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
        requireManageScenario(id);
        Scenario updated = service.updatePlan(id, plan != null ? plan : Map.of());
        log.info("[REST] PUT /scenarios/{}/plan -> status=200 body={}", id, safeJson(updated));
        return updated;
    }

    @GetMapping(value = "/{id}/schemas", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<String> listSchemas(@PathVariable("id") String id) throws IOException {
        log.info("[REST] GET /scenarios/{}/schemas", id);
        requireReadScenario(id);
        List<String> files = service.listSchemaFiles(id);
        log.info("[REST] GET /scenarios/{}/schemas -> status=200 body={}", id, safeJson(files));
        return files;
    }

    @GetMapping(value = "/{id}/schema", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> readSchema(@PathVariable("id") String id,
                                             @RequestParam("path") String path) throws IOException {
        log.info("[REST] GET /scenarios/{}/schema path={}", id, path);
        requireReadScenario(id);
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
        requireManageScenario(id);
        service.writeSchemaFile(id, path, body != null ? body : "");
        log.info("[REST] PUT /scenarios/{}/schema -> status=204", id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/{id}/templates", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<String> listTemplates(@PathVariable("id") String id) throws IOException {
        log.info("[REST] GET /scenarios/{}/templates", id);
        requireReadScenario(id);
        List<String> files = service.listTemplateFiles(id);
        log.info("[REST] GET /scenarios/{}/templates -> status=200 body={}", id, safeJson(files));
        return files;
    }

    @GetMapping(value = "/{id}/template", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> readTemplate(@PathVariable("id") String id,
                                               @RequestParam("path") String path) throws IOException {
        log.info("[REST] GET /scenarios/{}/template path={}", id, path);
        requireReadScenario(id);
        String text = service.readBundleFile(id, path);
        log.info("[REST] GET /scenarios/{}/template -> status=200 ({} chars)", id, text != null ? text.length() : 0);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(text);
    }

    @PutMapping(value = "/{id}/template", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<Void> writeTemplate(@PathVariable("id") String id,
                                              @RequestParam("path") String path,
                                              @RequestBody String body) throws IOException {
        int size = body != null ? body.length() : 0;
        log.info("[REST] PUT /scenarios/{}/template path={} ({} chars)", id, path, size);
        requireManageScenario(id);
        service.writeTemplate(id, path, body != null ? body : "");
        log.info("[REST] PUT /scenarios/{}/template -> status=204", id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/{id}/template/rename")
    public ResponseEntity<Void> renameTemplate(@PathVariable("id") String id,
                                               @RequestParam("from") String fromPath,
                                               @RequestParam("to") String toPath) throws IOException {
        log.info("[REST] POST /scenarios/{}/template/rename from={} to={}", id, fromPath, toPath);
        requireManageScenario(id);
        service.renameTemplate(id, fromPath, toPath);
        log.info("[REST] POST /scenarios/{}/template/rename -> status=204", id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping(value = "/{id}/template")
    public ResponseEntity<Void> deleteTemplate(@PathVariable("id") String id,
                                               @RequestParam("path") String path) throws IOException {
        log.info("[REST] DELETE /scenarios/{}/template path={}", id, path);
        requireManageScenario(id);
        service.deleteTemplate(id, path);
        log.info("[REST] DELETE /scenarios/{}/template -> status=204", id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/{id}/bundle", produces = "application/zip")
    public ResponseEntity<byte[]> downloadBundle(@PathVariable("id") String id) throws IOException {
        log.info("[REST] GET /scenarios/{}/bundle", id);
        requireReadScenario(id);
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

    @GetMapping(value = "/bundles/download", produces = "application/zip")
    public ResponseEntity<byte[]> downloadBundleByKey(@RequestParam("bundleKey") String bundleKey) throws IOException {
        log.info("[REST] GET /scenarios/bundles/download bundleKey={}", bundleKey);
        requireReadBundle(bundleKey);
        try {
            ScenarioService.BundleDownload bundle = service.downloadBundle(bundleKey);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentLength(bundle.bytes().length);
            headers.setContentDispositionFormData("attachment", bundle.fileName());
            log.info("[REST] GET /scenarios/bundles/download -> status=200 size={} filename={}",
                    bundle.bytes().length, bundle.fileName());
            return new ResponseEntity<>(bundle.bytes(), headers, HttpStatus.OK);
        } catch (IllegalArgumentException e) {
            log.warn("[REST] GET /scenarios/bundles/download -> status=400 bundleKey={} {}", bundleKey, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @PostMapping(
            value = "/bundles",
            consumes = "application/zip",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> uploadBundle(@RequestBody byte[] body) throws IOException {
        int size = body != null ? body.length : 0;
        log.info("[REST] POST /scenarios/bundles contentType=application/zip size={}", size);
        requireManageFolder("bundles");
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
    public ResponseEntity<?> replaceBundle(@PathVariable("id") String id,
                                           @RequestBody byte[] body) throws IOException {
        int size = body != null ? body.length : 0;
        log.info("[REST] PUT /scenarios/{}/bundle contentType=application/zip size={}", id, size);
        requireManageScenario(id);
        Scenario updated = service.replaceBundleFromZip(id, body);
        log.info("[REST] PUT /scenarios/{}/bundle -> status=200 body={}", id, safeJson(updated));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(updated);
    }

    @ExceptionHandler(BundleValidationException.class)
    ResponseEntity<BundleValidationResult> invalidBundle(BundleValidationException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(e.result());
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
        requireRunScenario(id);
        Scenario scenario = service.findAvailable(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
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

    private ResponseStatusException bundleReadException(IllegalArgumentException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        HttpStatus status = message.toLowerCase(java.util.Locale.ROOT).contains("not found")
                ? HttpStatus.NOT_FOUND
                : HttpStatus.BAD_REQUEST;
        return new ResponseStatusException(status, message, e);
    }

    private AuthenticatedUserDto currentUser() {
        return ScenarioManagerCurrentUserHolder.get();
    }

    private boolean canRead(AuthenticatedUserDto user, String scenarioId) {
        return service.findScenarioAccess(scenarioId)
                .map(access -> authorization.canRead(user, access))
                .orElse(false);
    }

    private boolean canReadBundleSummary(AuthenticatedUserDto user, ScenarioService.BundleTemplateSummary summary) {
        if (summary == null) {
            return false;
        }
        if (summary.id() != null && !summary.id().isBlank()) {
            return canRead(user, summary.id());
        }
        return service.findBundleAccess(summary.bundleKey())
                .map(access -> authorization.canRead(user, access))
                .orElse(false);
    }

    private void requireReadScenario(String id) {
        requireScenarioAccess(id, authorization::canRead, authorization.readDeniedMessage());
    }

    private void requireRunScenario(String id) {
        requireScenarioAccess(id, authorization::canRun, authorization.runDeniedMessage());
    }

    private void requireManageScenario(String id) {
        requireScenarioAccess(id, authorization::canManage, authorization.manageDeniedMessage());
    }

    private void requireReadBundle(String bundleKey) {
        requireBundleAccess(bundleKey, authorization::canRead, authorization.readDeniedMessage());
    }

    private void requireManageBundle(String bundleKey) {
        requireBundleAccess(bundleKey, authorization::canManage, authorization.manageDeniedMessage());
    }

    private void requireManageFolder(String folderPath) {
        AuthenticatedUserDto user = currentUser();
        if (!canManageFolder(user, folderPath)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, authorization.manageDeniedMessage());
        }
    }

    private void requireManageAllFolders() {
        AuthenticatedUserDto user = currentUser();
        if (!authorization.canManageDeployment(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, authorization.manageDeniedMessage());
        }
    }

    private void requireManagePocketHive() {
        AuthenticatedUserDto user = currentUser();
        if (!authorization.canManagePocketHive(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, authorization.manageDeniedMessage());
        }
    }

    private boolean canManageFolder(AuthenticatedUserDto user, String folderPath) {
        return authorization.canManageFolder(user, folderPath);
    }

    private void requireScenarioAccess(String scenarioId,
                                       ScenarioAccessCheck accessCheck,
                                       String deniedMessage) {
        AuthenticatedUserDto user = currentUser();
        if (user == null) {
            return;
        }
        ScenarioService.ScenarioAccessDescriptor access = service.findScenarioAccess(scenarioId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!accessCheck.isAllowed(user, access)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, deniedMessage);
        }
    }

    private void requireBundleAccess(String bundleKey,
                                     ScenarioAccessCheck accessCheck,
                                     String deniedMessage) {
        AuthenticatedUserDto user = currentUser();
        if (user == null) {
            return;
        }
        ScenarioService.ScenarioAccessDescriptor access = service.findBundleAccess(bundleKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bundle not found"));
        if (!accessCheck.isAllowed(user, access)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, deniedMessage);
        }
    }

    @FunctionalInterface
    private interface ScenarioAccessCheck {
        boolean isAllowed(AuthenticatedUserDto user, ScenarioService.ScenarioAccessDescriptor access);
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

    public record BundleMoveRequest(String bundleKey, String path) {
    }

    public record BundleFileWriteRequest(String content, String expectedRevision) {
    }

    public record BundleFileCreateRequest(String path, String content) {
    }

    public record BundleEntryRenameRequest(String path, String name) {
    }
}
