package io.pockethive.work.local.scheduler;

import io.pockethive.work.config.WorkConfigurationMode;
import io.pockethive.work.config.WorkInputMutationPolicy;
import io.pockethive.work.config.WorkMutationDescriptors;
import io.pockethive.work.config.WorkMutationRequest;
import io.pockethive.work.config.WorkerInputType;
import io.pockethive.work.config.input.InputRateParser;
import io.pockethive.work.config.input.InputScheduleField;
import io.pockethive.work.config.input.InputScheduleParser;
import java.util.Set;

/**
 * Responsibility: validate scheduler operational mutations through the neutral Work mutation port.
 * Must not: select inputs, write worker state or schedule work.
 * Contract: RESP-WORK-PATCH-POLICY — docs/architecture/runtime-responsibilities.md#resp-work-patch-policy.
 */
public final class SchedulerWorkInputMutationPolicy implements WorkInputMutationPolicy {
    private static final String ROOT = "inputs.scheduler.";
    private static final Set<String> PATHS = Set.of(ROOT + "ratePerSec", ROOT + "maxMessages", ROOT + "reset");
    private static final WorkMutationDescriptors DESCRIPTORS = new WorkMutationDescriptors(PATHS, Set.of());

    @Override
    public WorkerInputType type() { return WorkerInputType.SCHEDULER; }

    @Override
    public WorkMutationDescriptors descriptors() { return DESCRIPTORS; }

    @Override
    public void validate(WorkMutationRequest request) {
        String path = request.fieldPath();
        if (path.equals(ROOT + "ratePerSec")) {
            var result = new InputRateParser().validate(request.updatedValue(), path, WorkConfigurationMode.RESOLVED);
            reject(request, result.problems());
        } else if (path.equals(ROOT + "maxMessages")) {
            var result = new InputScheduleParser().validate(request.updatedValue(), InputScheduleField.MAX_MESSAGES,
                path, WorkConfigurationMode.RESOLVED);
            reject(request, result.problems());
        } else if (path.equals(ROOT + "reset")) {
            var result = new SchedulerResetParser().validate(request.updatedValue(), path, WorkConfigurationMode.RESOLVED);
            reject(request, result.problems());
        } else {
            throw new IllegalArgumentException("Unsupported scheduler mutable field '" + path + "'");
        }
    }

    private static void reject(WorkMutationRequest request, java.util.List<io.pockethive.work.config.WorkConfigurationProblem> problems) {
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("Runtime config-update has invalid operational IO field '" + request.fieldPath()
                + "' for worker '" + request.workerName() + "': " + problems.getFirst().message() + ".");
        }
    }
}
