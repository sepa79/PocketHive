package io.pockethive.work.local.csv;

import io.pockethive.work.config.WorkConfigurationMode;
import io.pockethive.work.config.WorkInputMutationPolicy;
import io.pockethive.work.config.WorkMutationDescriptors;
import io.pockethive.work.config.WorkMutationRequest;
import io.pockethive.work.config.WorkerInputType;
import java.util.LinkedHashMap;
import java.util.Set;

/**
 * Responsibility: validate CSV rate mutations against one complete candidate settings block.
 * Must not: select inputs, write worker state or read CSV files.
 * Contract: RESP-WORK-PATCH-POLICY — docs/architecture/runtime-responsibilities.md#resp-work-patch-policy.
 */
public final class CsvWorkInputMutationPolicy implements WorkInputMutationPolicy {
    private static final String RATE_PATH = "inputs.csv.ratePerSec";
    private static final WorkMutationDescriptors DESCRIPTORS = new WorkMutationDescriptors(Set.of(RATE_PATH), Set.of());

    private final CsvDatasetParser parser;

    public CsvWorkInputMutationPolicy(CsvDatasetParser parser) {
        this.parser = java.util.Objects.requireNonNull(parser, "parser");
    }

    @Override
    public WorkerInputType type() {
        return WorkerInputType.CSV_DATASET;
    }

    @Override
    public WorkMutationDescriptors descriptors() {
        return DESCRIPTORS;
    }

    @Override
    public void validate(WorkMutationRequest request) {
        if (!RATE_PATH.equals(request.fieldPath())) {
            throw new IllegalArgumentException("Unsupported CSV mutable field '" + request.fieldPath() + "'");
        }
        var candidate = new LinkedHashMap<String, Object>(request.startupSettings());
        candidate.putAll(request.previousSettings());
        candidate.putAll(request.settingsPatch());
        var result = parser.validate(candidate, CsvDatasetParser.PATH, WorkConfigurationMode.RESOLVED);
        if (!result.problems().isEmpty()) {
            throw new IllegalArgumentException("Runtime config-update has invalid operational IO field '" + request.fieldPath()
                + "' for worker '" + request.workerName() + "': " + result.problems().getFirst().path() + " "
                + result.problems().getFirst().message() + ".");
        }
    }
}
