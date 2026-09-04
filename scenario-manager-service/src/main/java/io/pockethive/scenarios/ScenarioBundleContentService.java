package io.pockethive.scenarios;

import io.pockethive.scenarios.validation.ScenarioBundleValidator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/**
 * Responsibility: Own raw scenario, plan, schema, and template authoring within an existing scenario bundle.
 * Must not: Discover bundles, own catalogue state, or implement generic workspace and ZIP publication operations.
 * Contract: docs/scenarios/SCENARIO_MANAGER_BUNDLE_REST.md and docs/scenarios/SCENARIO_PLAN_GUIDE.md.
 */
@Service
public class ScenarioBundleContentService {
    private final ScenarioService scenarios;
    private final ScenarioBundleValidator validator;

    public ScenarioBundleContentService(ScenarioService scenarios, ScenarioBundleValidator validator) {
        this.scenarios = scenarios;
        this.validator = validator;
    }

    public String readScenarioRaw(String scenarioId) throws IOException {
        return Files.readString(scenarios.scenarioDescriptorFile(scenarioId));
    }

    public void writeScenarioRaw(String scenarioId, String body) throws IOException {
        synchronized (scenarios) {
            if (body == null) {
                throw new IllegalArgumentException("Scenario body must not be null");
            }
            String trimmed = body.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("Scenario body must not be empty");
            }
            Path file = scenarios.scenarioDescriptorFile(scenarioId);
            Scenario scenario;
            try {
                scenario = validator.readScenarioDescriptorContent(trimmed);
            } catch (IOException e) {
                throw new IOException("Failed to parse scenario content: " + e.getMessage(), e);
            }
            requireMatchingId(scenarioId, scenario);
            Files.writeString(file, body);
            scenarios.reload();
        }
    }

    public Scenario writePlan(String scenarioId, Map<String, Object> plan) throws IOException {
        synchronized (scenarios) {
            Path file = scenarios.scenarioDescriptorFile(scenarioId);
            Scenario scenario = validator.readScenarioDescriptor(file);
            requireMatchingId(scenarioId, scenario);
            scenario.setPlan(plan == null || plan.isEmpty() ? null : plan);
            scenarios.writeScenarioDescriptor(file, scenario);
            scenarios.reload();
            return scenarios.find(scenarioId).orElse(scenario);
        }
    }

    public List<String> listSchemaFiles(String scenarioId) throws IOException {
        return listFiles(scenarioId, "schemas");
    }

    public void writeSchemaFile(String scenarioId, String relativePath, String content) throws IOException {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Schema path must not be null or blank");
        }
        Path bundle = scenarios.bundleDirFor(scenarioId);
        Path file = bundle.resolve(relativePath).normalize();
        if (!file.startsWith(bundle)) {
            throw new IllegalArgumentException("Invalid schema path");
        }
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, content);
    }

    public String readFile(String scenarioId, String relativePath) throws IOException {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("File path must not be null or blank");
        }
        Path bundle = scenarios.bundleDirFor(scenarioId);
        Path file = bundle.resolve(relativePath).normalize();
        if (!file.startsWith(bundle)) {
            throw new IllegalArgumentException("Invalid file path");
        }
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException(
                "File '%s' not found in bundle for scenario '%s'".formatted(relativePath, scenarioId));
        }
        return Files.readString(file);
    }

    public List<String> listTemplateFiles(String scenarioId) throws IOException {
        return listFiles(scenarioId, "templates");
    }

    public void writeTemplate(String scenarioId, String relativePath, String content) throws IOException {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Template path must not be null or blank");
        }
        Path bundle = scenarios.bundleDirFor(scenarioId);
        Path file = bundle.resolve(relativePath).normalize();
        if (!file.startsWith(bundle)) {
            throw new IllegalArgumentException("Invalid template path");
        }
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.exists(file) && !Files.isRegularFile(file)) {
            throw new IllegalArgumentException(
                "Template '%s' is not a file in bundle for scenario '%s'".formatted(relativePath, scenarioId));
        }
        Files.writeString(file, content);
    }

    public void renameTemplate(String scenarioId, String fromPath, String toPath) throws IOException {
        if (fromPath == null || fromPath.isBlank() || toPath == null || toPath.isBlank()) {
            throw new IllegalArgumentException("Template paths must not be null or blank");
        }
        Path bundle = scenarios.bundleDirFor(scenarioId);
        Path templates = bundle.resolve("templates").normalize();
        Path source = bundle.resolve(fromPath).normalize();
        Path target = bundle.resolve(toPath).normalize();
        if (!source.startsWith(bundle) || !target.startsWith(bundle)) {
            throw new IllegalArgumentException("Invalid template path");
        }
        if (!source.startsWith(templates) || !target.startsWith(templates)) {
            throw new IllegalArgumentException("Template paths must live under templates/");
        }
        if (source.equals(target)) {
            throw new IllegalArgumentException("Template paths must differ");
        }
        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException(
                "Template '%s' not found in bundle for scenario '%s'".formatted(fromPath, scenarioId));
        }
        if (Files.exists(target)) {
            throw new IllegalArgumentException(
                "Template '%s' already exists in bundle for scenario '%s'".formatted(toPath, scenarioId));
        }
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.move(source, target);
    }

    public void deleteTemplate(String scenarioId, String relativePath) throws IOException {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Template path must not be null or blank");
        }
        Path bundle = scenarios.bundleDirFor(scenarioId);
        Path templates = bundle.resolve("templates").normalize();
        Path file = bundle.resolve(relativePath).normalize();
        if (!file.startsWith(bundle)) {
            throw new IllegalArgumentException("Invalid template path");
        }
        if (!file.startsWith(templates)) {
            throw new IllegalArgumentException("Template paths must live under templates/");
        }
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException(
                "Template '%s' not found in bundle for scenario '%s'".formatted(relativePath, scenarioId));
        }
        Files.delete(file);
    }

    private List<String> listFiles(String scenarioId, String directoryName) throws IOException {
        Path bundle = scenarios.bundleDirFor(scenarioId);
        Path directory = bundle.resolve(directoryName).normalize();
        if (!directory.startsWith(bundle) || !Files.isDirectory(directory)) {
            return List.of();
        }
        List<String> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(directory)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                if (Files.isRegularFile(path)) {
                    files.add(bundle.relativize(path).toString().replace('\\', '/'));
                }
            }
        }
        files.sort(String::compareTo);
        return files;
    }

    private void requireMatchingId(String scenarioId, Scenario scenario) {
        if (scenario.getId() == null || scenario.getId().isBlank()) {
            throw new IllegalArgumentException("Scenario id must not be null or blank");
        }
        if (!scenarioId.equals(scenario.getId())) {
            throw new IllegalArgumentException(
                "Scenario id '" + scenario.getId() + "' does not match path id '" + scenarioId + "'");
        }
    }
}
