package io.pockethive.artemis.config;

import io.pockethive.artemis.api.ArtemisWorkIoType;
import io.pockethive.work.config.*;
import java.util.Objects;
import java.util.Set;

/**
 * Responsibility: declare Artemis input transport settings as startup-only.
 * Must not: parse settings, mutate accepted state or perform transport operations.
 * Contract: RESP-WORK-PATCH-POLICY — docs/architecture/runtime-responsibilities.md#resp-work-patch-policy.
 */
public final class ArtemisInputMutationPolicy implements WorkInputMutationPolicy {
    @Override public ArtemisWorkIoType type() { return ArtemisWorkIoType.ARTEMIS; }
    @Override public WorkMutationDescriptors descriptors() { return new WorkMutationDescriptors(Set.of(), Set.of()); }
    @Override public void validate(WorkMutationRequest request) { Objects.requireNonNull(request, "request"); }
}
