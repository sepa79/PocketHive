package io.pockethive.scenarios;

import io.pockethive.scenarios.validation.ScenarioBundleValidator;
import io.pockethive.swarm.model.SutEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Responsibility: Own canonical bundle-local SUT descriptor CRUD and listing.
 * Must not: Discover bundles, own catalogue state, or define SUT validation rules.
 * Contract: docs/scenarios/SCENARIO_MANAGER_BUNDLE_REST.md and docs/scenarios/SCENARIO_CONTRACT.md.
 */
@Service
public class ScenarioBundleSutService {
    private final ScenarioService scenarios;
    private final ScenarioBundleValidator validator;

    public ScenarioBundleSutService(ScenarioService scenarios, ScenarioBundleValidator validator) {
        this.scenarios = scenarios;
        this.validator = validator;
    }

    public List<String> list(String scenarioId) throws IOException {
        return validator.listCanonicalBundleSutIds(scenarios.bundleDirFor(scenarioId), scenarioId);
    }

    public SutEnvironment read(String scenarioId, String sutId) throws IOException {
        String canonicalSutId = canonicalSutId(sutId);
        Path sutDirectory = sutDirectory(scenarioId, canonicalSutId);
        if (!Files.isDirectory(sutDirectory)) {
            throw new IllegalArgumentException(
                "SUT '%s' not found in bundle for scenario '%s'".formatted(canonicalSutId, scenarioId));
        }
        try {
            return validator.readBundleSutDescriptor(sutDirectory, canonicalSutId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "Failed to parse SUT '%s' for scenario '%s': %s"
                    .formatted(canonicalSutId, scenarioId, e.getMessage()),
                e);
        }
    }

    public String readRaw(String scenarioId, String sutId) throws IOException {
        Path sutDirectory = sutDirectory(scenarioId, canonicalSutId(sutId));
        if (!Files.isDirectory(sutDirectory)) {
            return null;
        }
        Path file = ScenarioBundleLayout.sutDescriptorFile(sutDirectory);
        if (!file.startsWith(sutDirectory) || !Files.isRegularFile(file)) {
            return null;
        }
        return Files.readString(file);
    }

    public void writeRaw(String scenarioId, String sutId, String raw) throws IOException {
        synchronized (scenarios) {
            String canonicalSutId = canonicalSutId(sutId);
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("sut.yaml must not be empty");
            }
            SutEnvironment environment;
            try {
                environment = validator.readSutEnvironmentYaml(raw);
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to parse sut.yaml", e);
            }
            validator.requireCanonicalSutDescriptor(environment, canonicalSutId);

            Path sutDirectory = sutDirectory(scenarioId, canonicalSutId);
            Files.createDirectories(sutDirectory);
            Path file = ScenarioBundleLayout.sutDescriptorFile(sutDirectory);
            if (!file.startsWith(sutDirectory)) {
                throw new IllegalArgumentException("Invalid sut.yaml path");
            }
            Files.writeString(file, raw);
        }
    }

    public void delete(String scenarioId, String sutId) throws IOException {
        synchronized (scenarios) {
            String canonicalSutId = canonicalSutId(sutId);
            Path sutDirectory = sutDirectory(scenarioId, canonicalSutId);
            if (!Files.isDirectory(sutDirectory)) {
                throw new IllegalArgumentException(
                    "SUT '%s' not found in bundle for scenario '%s'".formatted(canonicalSutId, scenarioId));
            }
            ScenarioFileTreeOperations.clear(sutDirectory);
            Files.deleteIfExists(sutDirectory);
        }
    }

    private Path sutDirectory(String scenarioId, String sutId) {
        Path bundle = scenarios.bundleDirFor(scenarioId);
        Path sutDirectory = bundle.resolve("sut").resolve(sutId).normalize();
        if (!sutDirectory.startsWith(bundle)) {
            throw new IllegalArgumentException("Invalid sutId");
        }
        return sutDirectory;
    }

    private String canonicalSutId(String sutId) {
        if (sutId == null || sutId.isBlank()) {
            throw new IllegalArgumentException("sutId must not be blank");
        }
        String cleaned = Paths.get(sutId).getFileName().toString();
        if (!cleaned.equals(sutId) || cleaned.contains("..") || cleaned.isBlank()) {
            throw new IllegalArgumentException("Invalid sutId");
        }
        return cleaned;
    }
}
