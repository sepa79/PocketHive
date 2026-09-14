package io.pockethive.worker.sdk.testing;

import io.pockethive.work.config.*;
import java.util.Objects;
import java.util.Set;

/**
 * Responsibility: declare test adapter settings immutable to the canonical patch policy.
 * Must not: write accepted worker state or apply transports.
 * Contract: RESP-WORK-PATCH-POLICY — docs/architecture/work-plane-boundaries.md#10-remaining-workplane-isolation-target--rabbit-plus-a-test-adapter.
 */
public final class InMemoryWorkMutationPolicy implements WorkInputMutationPolicy, WorkOutputMutationPolicy {
    @Override public WorkIoType type() { return InMemoryWorkType.MEMORY; }
    @Override public WorkMutationDescriptors descriptors() { return new WorkMutationDescriptors(Set.of(), Set.of()); }
    @Override public void validate(WorkMutationRequest request) { Objects.requireNonNull(request); }
}
