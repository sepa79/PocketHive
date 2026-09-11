package io.pockethive.rabbit.config;


import io.pockethive.work.config.WorkOutputMutationPolicy;
import io.pockethive.work.config.WorkMutationDescriptors;
import io.pockethive.work.config.WorkMutationRequest;
import io.pockethive.work.config.WorkerOutputType;

/**
 * Responsibility: declare RabbitMQ output mutation ownership with no live mutable fields.
 * Must not: validate Rabbit connection settings, mutate state or select messaging transports.
 * Contract: RESP-WORK-PATCH-POLICY — docs/architecture/runtime-responsibilities.md#resp-work-patch-policy.
 */
public final class RabbitOutputMutationPolicy implements WorkOutputMutationPolicy {
    @Override public WorkerOutputType type() { return WorkerOutputType.RABBITMQ; }
    @Override public WorkMutationDescriptors descriptors() { return new WorkMutationDescriptors(java.util.Set.of(), java.util.Set.of()); }
    @Override public void validate(WorkMutationRequest request) { java.util.Objects.requireNonNull(request, "request"); }
}
