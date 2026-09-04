package io.pockethive.scenarios;

import io.pockethive.scenarios.validation.ScenarioBundleValidator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Responsibility: Own reading, validating, resolving, and writing a scenario bundle's variables document.
 * Must not: Discover scenario bundles, mutate the scenario catalogue, or define variables validation rules.
 * Contract: docs/scenarios/SCENARIO_VARIABLES.md.
 */
@Service
public class ScenarioVariablesService {
    private final ScenarioService scenarios;
    private final ScenarioBundleValidator validator;
    private final Object writeLock = new Object();

    public ScenarioVariablesService(ScenarioService scenarios, ScenarioBundleValidator validator) {
        this.scenarios = scenarios;
        this.validator = validator;
    }

    public String readRaw(String scenarioId) throws IOException {
        Path bundle = scenarios.bundleDirFor(scenarioId);
        if (!Files.isDirectory(bundle)) {
            return null;
        }
        Path file = ScenarioBundleLayout.variablesFile(bundle);
        if (!file.startsWith(bundle)) {
            throw new IllegalArgumentException("Invalid variables path");
        }
        if (!Files.isRegularFile(file)) {
            return null;
        }
        return Files.readString(file);
    }

    public VariablesDocument parse(String raw) {
        return validator.parseVariables(raw);
    }

    public VariablesValidationResult validate(String scenarioId, VariablesDocument document) throws IOException {
        return validator.validateVariables(
            document,
            validator.listCanonicalBundleSutIds(scenarios.bundleDirFor(scenarioId), scenarioId));
    }

    public VariablesResolutionResult resolve(
        String scenarioId,
        String variablesProfileId,
        String sutId
    ) throws IOException {
        String raw = readRaw(scenarioId);
        if (raw == null) {
            return new VariablesResolutionResult(Map.of(), List.of());
        }
        VariablesDocument document = parse(raw);
        VariablesValidationResult validation = validate(scenarioId, document);

        String profile = normalizedSelection(variablesProfileId);
        String sut = normalizedSelection(sutId);

        Map<String, ScenarioVariableDefinition> byName = new LinkedHashMap<>();
        for (ScenarioVariableDefinition definition : document.definitions()) {
            byName.put(definition.name().trim(), definition);
        }
        boolean needsProfile = byName.values().stream().anyMatch(definition ->
            definition.scope() == ScenarioVariableScope.GLOBAL || definition.scope() == ScenarioVariableScope.SUT);
        boolean needsSut = byName.values().stream().anyMatch(definition ->
            definition.scope() == ScenarioVariableScope.SUT);
        if (needsProfile && profile == null) {
            throw new IllegalArgumentException("variablesProfileId is required for this scenario");
        }
        if (needsSut && sut == null) {
            throw new IllegalArgumentException("sutId is required for this scenario (sut-scoped variables exist)");
        }

        ScenarioVariableValues values = document.values();
        Map<String, Map<String, Object>> global =
            values == null || values.global() == null ? Map.of() : values.global();
        Map<String, Map<String, Map<String, Object>>> sutValues =
            values == null || values.sut() == null ? Map.of() : values.sut();

        Map<String, Object> resolved = new LinkedHashMap<>();
        for (ScenarioVariableDefinition definition : document.definitions()) {
            String name = definition.name().trim();
            Object value = null;
            if (definition.scope() == ScenarioVariableScope.GLOBAL) {
                value = global.getOrDefault(profile, Map.of()).get(name);
            } else if (definition.scope() == ScenarioVariableScope.SUT) {
                value = sutValues.getOrDefault(profile, Map.of()).getOrDefault(sut, Map.of()).get(name);
            }
            if (value == null) {
                if (Boolean.TRUE.equals(definition.required())) {
                    throw new IllegalArgumentException("Missing required variable '%s'".formatted(name));
                }
                continue;
            }
            if (definition.type() == ScenarioVariableType.FLOAT
                && value instanceof Number number
                && !(value instanceof Double)) {
                value = number.doubleValue();
            }
            resolved.put(name, value);
        }

        return new VariablesResolutionResult(Map.copyOf(resolved), validation.warnings());
    }

    public VariablesValidationResult write(String scenarioId, String raw) throws IOException {
        synchronized (writeLock) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("%s must not be empty".formatted(ScenarioBundleLayout.VARIABLES_FILE));
            }
            VariablesDocument document = parse(raw);
            VariablesValidationResult validation = validate(scenarioId, document);

            Path bundle = scenarios.bundleDirFor(scenarioId);
            Path file = ScenarioBundleLayout.variablesFile(bundle);
            if (!file.startsWith(bundle)) {
                throw new IllegalArgumentException("Invalid variables path");
            }
            Files.createDirectories(bundle);
            Files.writeString(file, raw);
            return validation;
        }
    }

    private String normalizedSelection(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
