package io.pockethive.scenarios;

import io.pockethive.scenarios.validation.BundleValidationException;
import io.pockethive.scenarios.validation.BundleValidationInput;
import io.pockethive.scenarios.validation.BundleValidationResult;
import io.pockethive.scenarios.validation.BundleValidationSource;
import io.pockethive.scenarios.validation.ScenarioBundleValidator;
import io.pockethive.scenarios.validation.ValidationFinding;
import io.pockethive.scenarios.validation.ValidationRun;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.stereotype.Service;

/**
 * Responsibility: Own scenario bundle ZIP validation, safe extraction, and catalogue publication workflows.
 * Must not: Own catalogue identity, duplicate/quarantine state, or define bundle validation rules.
 * Contract: docs/scenarios/SCENARIO_BUNDLE_DIAGNOSTICS.md and docs/scenarios/SCENARIO_MANAGER_BUNDLE_REST.md.
 */
@Service
public class ScenarioBundlePublicationService {
    private static final String UPLOAD_TEMP_PREFIX = "pockethive-scenario-upload-";

    private final ScenarioService scenarios;
    private final ScenarioBundleOrganizationService organization;
    private final ScenarioBundleValidator validator;

    public ScenarioBundlePublicationService(ScenarioService scenarios,
                                            ScenarioBundleOrganizationService organization,
                                            ScenarioBundleValidator validator) {
        this.scenarios = scenarios;
        this.organization = organization;
        this.validator = validator;
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
                writeBundle(validatedRoot(validation), organization.defaultUploadDirectory(scenario.getId()));
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
                writeBundle(validatedRoot(validation), target);
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
        if (candidate.scenario() == null) {
            ValidationFinding finding = validator.defunctBundleFinding(
                candidate.bundlePath(),
                candidate.defunctReason());
            return validator.resultOf(
                BundleValidationSource.SCENARIO_MANAGER,
                candidate.bundleKey(),
                candidate.bundlePath(),
                null,
                null,
                null,
                candidate.bundleDirectory(),
                List.of(finding));
        }
        return validator.validate(new BundleValidationInput(
            BundleValidationSource.SCENARIO_MANAGER,
            candidate.bundleDirectory(),
            candidate.bundleKey(),
            candidate.bundlePath(),
            candidate.scenario(),
            candidate.seedFindings(),
            null));
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

    private void writeBundle(Path source, Path target) throws IOException {
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
