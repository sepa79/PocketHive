package io.pockethive.scenarios;

import io.pockethive.scenarios.validation.BundleValidationException;
import io.pockethive.scenarios.validation.BundleValidationInput;
import io.pockethive.scenarios.validation.BundleValidationResult;
import io.pockethive.scenarios.validation.BundleValidationSource;
import io.pockethive.scenarios.validation.ScenarioBundleValidator;
import io.pockethive.scenarios.validation.ValidationRun;
import io.pockethive.swarm.model.SutEnvironment;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Responsibility: provide the application API for bundle publication, export and authoring operations.
 * Must not: own HTTP mapping, grant policy, catalogue identity or bundle validation rules.
 * Contract: RESP-SCENARIO-BUNDLE-API — docs/architecture/runtime-responsibilities.md#resp-scenario-bundle-api;
 * RESP-SCENARIO-BUNDLE-DOWNLOAD — docs/architecture/runtime-responsibilities.md#resp-scenario-bundle-download.
 */
@Service
public class ScenarioBundleService {
    private static final Logger log = LoggerFactory.getLogger(ScenarioBundleService.class);
    private static final String UPLOAD_TEMP_PREFIX = "pockethive-scenario-upload-";

    private final ScenarioService scenarios;
    private final ScenarioBundleOrganizationService organization;
    private final ScenarioBundleValidator validator;

    private final ScenarioBundleZipExporter exporter;
    private final ScenarioBundleWorkspaceService workspace;
    private final ScenarioBundleContentService content;
    private final ScenarioBundleSutService suts;

    public ScenarioBundleService(ScenarioService scenarios,
                                 ScenarioBundleOrganizationService organization,
                                 ScenarioBundleValidator validator,
                                 ScenarioBundleZipExporter exporter,
                                 ScenarioBundleWorkspaceService workspace,
                                 ScenarioBundleContentService content,
                                 ScenarioBundleSutService suts) {
        this.scenarios = scenarios;
        this.organization = organization;
        this.validator = validator;
        this.exporter = exporter;
        this.workspace = workspace;
        this.content = content;
        this.suts = suts;
    }

    public Scenario create(byte[] zipBytes) throws IOException {
        synchronized (scenarios) {
            Path uploaded = unpackForPublication(zipBytes);
            try {
                ValidationRun validation = validateUploaded(uploaded, null);
                requireSuccessful(validation);
                Scenario scenario = validatedScenario(validation);
                if (scenarios.hasDiscoveredScenarioId(scenario.getId())) {
                    throw new BundleValidationException(
                        validator.duplicateScenarioValidationResult(scenario.getId()));
                }
                Path target = organization.defaultUploadDirectory(scenario.getId());
                Files.createDirectories(target.getParent());
                Files.createDirectory(target);
                ScenarioFileTreeOperations.copy(validatedRoot(validation), target);
                scenarios.reload();
                return scenarios.find(scenario.getId()).orElse(scenario);
            } finally {
                cleanup(uploaded);
            }
        }
    }

    public Scenario replace(String expectedScenarioId, byte[] zipBytes) throws IOException {
        if (expectedScenarioId == null || expectedScenarioId.isBlank()) {
            throw new IllegalArgumentException("Scenario id must not be null or blank");
        }
        synchronized (scenarios) {
            Path uploaded = unpackForPublication(zipBytes);
            try {
                ValidationRun validation = validateUploaded(uploaded, expectedScenarioId);
                requireSuccessful(validation);
                Scenario scenario = validatedScenario(validation);
                Path target = scenarios.bundleDirForExistingOrDefault(scenario.getId());
                replaceBundleContents(validatedRoot(validation), target);
                scenarios.reload();
                return scenarios.find(scenario.getId()).orElse(scenario);
            } finally {
                cleanup(uploaded);
            }
        }
    }

    public BundleValidationResult validateZip(byte[] zipBytes) throws IOException {
        Path uploaded = null;
        try {
            uploaded = unpack(zipBytes);
            return validateUploaded(uploaded, null).result();
        } catch (IllegalArgumentException e) {
            return validator.uploadedBundleValidationResult(e);
        } finally {
            if (uploaded != null) {
                cleanup(uploaded);
            }
        }
    }

    public BundleValidationResult validateExisting(String bundleKey) throws IOException {
        ScenarioBundleValidationCandidate candidate = scenarios.validationCandidate(bundleKey);
        return validator.validate(new BundleValidationInput(
            BundleValidationSource.SCENARIO_MANAGER,
            candidate.bundleDirectory(),
            candidate.bundleKey(),
            candidate.bundlePath(),
            null,
            candidate.seedFindings(),
            null));
    }

    public BundleDownload downloadByScenarioId(String id) throws IOException {
        Scenario scenario = scenarios.find(id).orElseThrow(ScenarioDownloadNotFoundException::new);
        Path root;
        try {
            root = scenarios.bundleDirFor(scenario.getId());
        } catch (IllegalArgumentException e) {
            throw new ScenarioDownloadNotFoundException("Scenario bundle not found", e);
        }
        if (!Files.isDirectory(root)) {
            log.warn("Bundle directory {} for scenario '{}' not found", root, id);
            throw new ScenarioDownloadNotFoundException("Scenario bundle not found");
        }
        return new BundleDownload(exporter.export(root), scenario.getId() + "-bundle.zip");
    }

    public BundleDownload downloadByBundleKey(String bundleKey) throws IOException {
        synchronized (scenarios) {
            ScenarioBundleWorkspaceLocation location = scenarios.bundleWorkspaceLocation(bundleKey);
            if (location.root() == null || !Files.isDirectory(location.root())) {
                throw new IllegalArgumentException("Bundle '%s' not found".formatted(location.bundleKey()));
            }
            return new BundleDownload(exporter.export(location.root()),
                scenarios.fallbackBundleName(location.bundlePath()) + "-bundle.zip");
        }
    }

    public BundleTree readTree(String bundleKey) throws IOException {
        return workspace.readTree(bundleKey);
    }

    public BundleFilePayload readBundleFile(String bundleKey, String relativePath) throws IOException {
        return workspace.readFile(bundleKey, relativePath);
    }

    public BundleFileWriteResult writeBundleFile(String bundleKey, String relativePath, String content, String expectedRevision) throws IOException {
        return workspace.writeFile(bundleKey, relativePath, content, expectedRevision);
    }

    public BundleFilePayload createBundleFile(String bundleKey, String relativePath, String content) throws IOException {
        return workspace.createFile(bundleKey, relativePath, content);
    }

    public void createBundleFolder(String bundleKey, String relativePath) throws IOException {
        workspace.createFolder(bundleKey, relativePath);
    }

    public void renameBundleEntry(String bundleKey, String relativePath, String name) throws IOException {
        workspace.renameEntry(bundleKey, relativePath, name);
    }

    public void deleteBundleEntry(String bundleKey, String relativePath) throws IOException {
        workspace.deleteEntry(bundleKey, relativePath);
    }

    public List<String> listFolders() throws IOException {
        return organization.listFolders();
    }

    public void createFolder(String folderPath) throws IOException {
        organization.createFolder(folderPath);
    }

    public void deleteFolder(String folderPath) throws IOException {
        organization.deleteFolder(folderPath);
    }

    public void moveScenario(String scenarioId, String folderPath) throws IOException {
        organization.moveScenario(scenarioId, folderPath);
    }

    public void moveBundle(String bundleKey, String folderPath) throws IOException {
        organization.moveBundle(bundleKey, folderPath);
    }

    public void deleteBundle(String bundleKey) throws IOException {
        organization.deleteBundle(bundleKey);
    }

    public String uploadFolder() {
        return organization.uploadFolder();
    }

    public String readScenarioRaw(String scenarioId) throws IOException {
        return content.readScenarioRaw(scenarioId);
    }

    public void writeScenarioRaw(String scenarioId, String body) throws IOException {
        content.writeScenarioRaw(scenarioId, body);
    }

    public Scenario writePlan(String scenarioId, Map<String, Object> plan) throws IOException {
        return content.writePlan(scenarioId, plan);
    }

    public List<String> listSchemaFiles(String scenarioId) throws IOException {
        return content.listSchemaFiles(scenarioId);
    }

    public void writeSchemaFile(String scenarioId, String relativePath, String content) throws IOException {
        this.content.writeSchemaFile(scenarioId, relativePath, content);
    }

    public String readScenarioFile(String scenarioId, String relativePath) throws IOException {
        return content.readFile(scenarioId, relativePath);
    }

    public List<String> listTemplateFiles(String scenarioId) throws IOException {
        return content.listTemplateFiles(scenarioId);
    }

    public void writeTemplate(String scenarioId, String relativePath, String content) throws IOException {
        this.content.writeTemplate(scenarioId, relativePath, content);
    }

    public void renameTemplate(String scenarioId, String fromPath, String toPath) throws IOException {
        content.renameTemplate(scenarioId, fromPath, toPath);
    }

    public void deleteTemplate(String scenarioId, String relativePath) throws IOException {
        content.deleteTemplate(scenarioId, relativePath);
    }

    public List<String> listSuts(String scenarioId) throws IOException {
        return suts.list(scenarioId);
    }

    public SutEnvironment readSut(String scenarioId, String sutId) throws IOException {
        return suts.read(scenarioId, sutId);
    }

    public String readSutRaw(String scenarioId, String sutId) throws IOException {
        return suts.readRaw(scenarioId, sutId);
    }

    public void writeSutRaw(String scenarioId, String sutId, String raw) throws IOException {
        suts.writeRaw(scenarioId, sutId, raw);
    }

    public void deleteSut(String scenarioId, String sutId) throws IOException {
        suts.delete(scenarioId, sutId);
    }

    private Path unpackForPublication(byte[] zipBytes) throws IOException {
        try {
            return unpack(zipBytes);
        } catch (IllegalArgumentException e) {
            throw new BundleValidationException(validator.uploadedBundleValidationResult(e));
        }
    }

    private Path unpack(byte[] zipBytes) throws IOException {
        if (zipBytes == null || zipBytes.length == 0) {
            throw new IllegalArgumentException("Zip payload must not be empty");
        }
        Path tempRoot = Files.createTempDirectory(UPLOAD_TEMP_PREFIX);
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name == null || name.isBlank()) {
                    continue;
                }
                if (name.startsWith("/") || name.contains("..")) {
                    throw new IllegalArgumentException("Invalid entry path '%s'".formatted(name));
                }
                Path destination = tempRoot.resolve(name).normalize();
                if (!destination.startsWith(tempRoot)) {
                    throw new IllegalArgumentException("Invalid entry path '%s'".formatted(name));
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(destination);
                } else {
                    Path parent = destination.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(zip, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return tempRoot;
        } catch (IOException | RuntimeException e) {
            cleanup(tempRoot);
            throw e;
        }
    }

    private ValidationRun validateUploaded(Path uploaded, String expectedId)
        throws IOException {
        return validator.validateWithContext(new BundleValidationInput(
            BundleValidationSource.UPLOADED_ZIP,
            uploaded,
            null,
            null,
            null,
            List.of(),
            expectedId));
    }

    private void requireSuccessful(ValidationRun validation) {
        if (!validation.result().ok()) {
            throw new BundleValidationException(validation.result());
        }
    }

    private Scenario validatedScenario(ValidationRun validation) {
        Scenario scenario = validation.scenario();
        if (scenario == null || scenario.getId() == null || scenario.getId().isBlank()) {
            throw new IllegalStateException("Canonical bundle validation returned ok without a scenario id");
        }
        return scenario;
    }

    private Path validatedRoot(ValidationRun validation) {
        Path root = validation.bundleRoot();
        if (root == null || !Files.isDirectory(root)) {
            throw new IllegalStateException("Canonical bundle validation returned ok without a bundle root");
        }
        return root;
    }

    private void replaceBundleContents(Path source, Path target) throws IOException {
        if (Files.exists(target)) {
            ScenarioFileTreeOperations.clear(target);
        }
        Files.createDirectories(target);
        ScenarioFileTreeOperations.copy(source, target);
    }

    private void cleanup(Path uploaded) throws IOException {
        ScenarioFileTreeOperations.clear(uploaded);
        Files.deleteIfExists(uploaded);
    }
}
