package io.pockethive.rabbit.config;

import io.pockethive.work.config.WorkInputMutationPolicy;
import io.pockethive.work.config.WorkMutationDescriptors;
import io.pockethive.work.config.WorkMutationRequest;
import io.pockethive.work.config.WorkerInputType;

/**
 * Responsibility: declare RabbitMQ input mutation ownership with no live mutable fields.
 * Must not: validate Rabbit connection settings, mutate state or select messaging transports.
 * Contract: RESP-WORK-PATCH-POLICY — docs/architecture/runtime-responsibilities.md#resp-work-patch-policy.
 */
public final class RabbitInputMutationPolicy implements WorkInputMutationPolicy {
    @Override public WorkerInputType type() { return WorkerInputType.RABBITMQ; }
    @Override public WorkMutationDescriptors descriptors() { return new WorkMutationDescriptors(java.util.Set.of(), java.util.Set.of()); }
    @Override public void validate(WorkMutationRequest request) { java.util.Objects.requireNonNull(request, "request"); }
}
