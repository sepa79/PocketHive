package io.pockethive.work.config;

/**
 * Responsibility: describe and validate selected output adapter mutations through a neutral port.
 * Must not: write worker state, merge patches or expose adapter settings types.
 * Contract: RESP-WORK-PATCH-POLICY — docs/architecture/runtime-responsibilities.md#resp-work-patch-policy.
 */
public interface WorkOutputMutationPolicy {
    WorkerOutputType type();

    WorkMutationDescriptors descriptors();

    void validate(WorkMutationRequest request);
}
