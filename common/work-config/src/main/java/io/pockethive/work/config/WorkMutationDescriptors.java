package io.pockethive.work.config;

import java.util.Objects;
import java.util.Set;

/**
 * Responsibility: declare one adapter's mutable configuration fields.
 * Must not: validate values, write worker state or select an adapter.
 * Contract: RESP-WORK-PATCH-POLICY — docs/architecture/runtime-responsibilities.md#resp-work-patch-policy.
 */
public record WorkMutationDescriptors(Set<String> liveMutablePaths, Set<String> disabledOnlyPaths) {
    public WorkMutationDescriptors {
        liveMutablePaths = Set.copyOf(Objects.requireNonNull(liveMutablePaths, "liveMutablePaths"));
        disabledOnlyPaths = Set.copyOf(Objects.requireNonNull(disabledOnlyPaths, "disabledOnlyPaths"));
        if (!liveMutablePaths.containsAll(disabledOnlyPaths)) {
            throw new IllegalArgumentException("disabledOnlyPaths must be a subset of liveMutablePaths");
        }
    }
}
