package io.pockethive.work.config;

import java.util.Objects;
import java.util.Set;

/**
 * Responsibility: declare the explicit NONE output mutation policy.
 * Must not: accept output settings, mutate worker state or select another output.
 * Contract: RESP-WORK-PATCH-POLICY — docs/architecture/runtime-responsibilities.md#resp-work-patch-policy.
 */
public final class NoOutputMutationPolicy implements WorkOutputMutationPolicy {
    private static final WorkMutationDescriptors DESCRIPTORS =
        new WorkMutationDescriptors(Set.of(), Set.of());

    @Override
    public WorkerOutputType type() {
        return WorkerOutputType.NONE;
    }

    @Override
    public WorkMutationDescriptors descriptors() {
        return DESCRIPTORS;
    }

    @Override
    public void validate(WorkMutationRequest request) {
        Objects.requireNonNull(request, "request");
    }
}
