package io.pockethive.scenarios;

import io.pockethive.scenarios.validation.BundleValidationException;
import io.pockethive.scenarios.validation.BundleValidationInput;
import io.pockethive.scenarios.validation.BundleValidationResult;
import io.pockethive.scenarios.validation.BundleValidationSource;
import io.pockethive.scenarios.validation.ScenarioBundleValidator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Responsibility: Validate and materialize an immutable scenario bundle into one swarm runtime directory.
 * Must not: Discover bundles, own catalogue state, or clear a runtime target before validation succeeds.
 * Contract: docs/scenarios/SCENARIO_CONTRACT.md and common control-plane filesystem contract.
 */
@Service
public class ScenarioRuntimeMaterializer {
    private static final Logger logger = LoggerFactory.getLogger(ScenarioRuntimeMaterializer.class);

    private final ScenarioService scenarios;
    private final ScenarioBundleValidator validator;

    public ScenarioRuntimeMaterializer(ScenarioService scenarios, ScenarioBundleValidator validator) {
        this.scenarios = scenarios;
        this.validator = validator;
    }

    public Path materialize(String scenarioId, String swarmId) throws IOException {
        if (scenarioId == null || scenarioId.isBlank()) {
            throw new IllegalArgumentException("scenarioId must not be null or blank");
        }
        if (swarmId == null || swarmId.isBlank()) {
            throw new IllegalArgumentException("swarmId must not be null or blank");
        }
        synchronized (scenarios) {
            String normalizedScenarioId = scenarioId.trim();
            ScenarioRuntimeCandidate candidate = scenarios.runtimeCandidate(normalizedScenarioId);
            if (candidate == null) {
                throw new IllegalArgumentException("Scenario '%s' not found".formatted(normalizedScenarioId));
            }
            requireValid(candidate);

            Path target = scenarios.runtimeDir(swarmId);
            if (Files.exists(target)) {
                ScenarioFileTreeOperations.clear(target);
            }
            Files.createDirectories(target);
            if (candidate.bundleDirectory() != null && Files.isDirectory(candidate.bundleDirectory())) {
                ScenarioFileTreeOperations.copy(candidate.bundleDirectory(), target);
            } else {
                logger.info("No bundle directory found for scenario '{}'; runtime directory {} will be empty",
                    normalizedScenarioId, target);
            }
            return target;
        }
    }

    private void requireValid(ScenarioRuntimeCandidate candidate) throws IOException {
        BundleValidationResult validation = validator.validate(new BundleValidationInput(
            BundleValidationSource.SCENARIO_MANAGER,
            candidate.bundleDirectory(),
            candidate.bundleKey(),
            candidate.bundlePath(),
            null,
            candidate.seedFindings(),
            candidate.scenarioId()));
        if (!validation.ok()) {
            throw new BundleValidationException(validation);
        }
    }
}
