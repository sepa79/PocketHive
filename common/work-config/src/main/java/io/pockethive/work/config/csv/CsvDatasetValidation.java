package io.pockethive.work.config.csv;

import io.pockethive.work.config.WorkConfigurationProblem;
import java.util.List;
import java.util.Objects;

/**
 * Responsibility: expose resolved CSV settings or validation errors/deferred paths.
 * Must not: validate declarations or expose partially validated settings.
 * Contract: RESP-WORK-CSV-SETTINGS — docs/architecture/runtime-responsibilities.md#resp-work-csv-settings.
 */
public record CsvDatasetValidation(CsvDatasetSettings settings, List<WorkConfigurationProblem> problems,
                                   List<String> deferredPaths) {
    public CsvDatasetValidation {
        problems = List.copyOf(problems);
        deferredPaths = List.copyOf(deferredPaths);
        settings = problems.isEmpty() && deferredPaths.isEmpty() ? Objects.requireNonNull(settings) : null;
    }
}
