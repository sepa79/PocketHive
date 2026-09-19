package io.pockethive.redis.config;

import io.pockethive.work.config.WorkMutationDescriptors;
import io.pockethive.work.config.WorkMutationRequest;
import io.pockethive.work.config.WorkOutputMutationPolicy;
import io.pockethive.work.config.WorkerOutputType;
import java.util.Set;

/**
 * Responsibility: declare Redis output wiring immutable through the neutral Work mutation port.
 * Must not: validate unselected output blocks, write worker state or access Redis.
 * Contract: RESP-WORK-PATCH-POLICY — docs/architecture/runtime-responsibilities.md#resp-work-patch-policy.
 */
public final class RedisWorkOutputMutationPolicy implements WorkOutputMutationPolicy {
    private static final WorkMutationDescriptors DESCRIPTORS = new WorkMutationDescriptors(Set.of(), Set.of());

    @Override
    public WorkerOutputType type() {
        return WorkerOutputType.REDIS;
    }

    @Override
    public WorkMutationDescriptors descriptors() {
        return DESCRIPTORS;
    }

    @Override
    public void validate(WorkMutationRequest request) {
        throw new IllegalArgumentException("Redis output has no mutable fields: " + request.fieldPath());
    }
}
