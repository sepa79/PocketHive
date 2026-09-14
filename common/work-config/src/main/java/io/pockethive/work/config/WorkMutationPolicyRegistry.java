package io.pockethive.work.config;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Responsibility: select exactly one mutation policy for each declared IO adapter type.
 * Must not: provide defaults, infer a type or validate a patch.
 * Contract: RESP-WORK-PATCH-POLICY — docs/architecture/runtime-responsibilities.md#resp-work-patch-policy.
 */
public final class WorkMutationPolicyRegistry {
    private final List<WorkInputMutationPolicy> inputPolicies;
    private final List<WorkOutputMutationPolicy> outputPolicies;

    public WorkMutationPolicyRegistry(List<WorkInputMutationPolicy> inputPolicies, List<WorkOutputMutationPolicy> outputPolicies) {
        var suppliedInputs = Objects.requireNonNull(inputPolicies, "inputPolicies");
        var suppliedOutputs = Objects.requireNonNull(outputPolicies, "outputPolicies");
        suppliedInputs.forEach(this::validateInputPolicy);
        suppliedOutputs.forEach(this::validateOutputPolicy);
        this.inputPolicies = List.copyOf(suppliedInputs);
        this.outputPolicies = List.copyOf(suppliedOutputs);
    }

    public WorkInputMutationPolicy inputPolicy(WorkIoType type) {
        Objects.requireNonNull(type, "type");
        return exactlyOne(inputPolicies.stream().filter(policy -> policy.type().equals(type)).toList(), "input", type);
    }

    public WorkOutputMutationPolicy outputPolicy(WorkIoType type) {
        Objects.requireNonNull(type, "type");
        return exactlyOne(outputPolicies.stream().filter(policy -> policy.type().equals(type)).toList(), "output", type);
    }

    public boolean isLiveMutableIoPath(String path) {
        return liveMutableIoPaths().contains(path);
    }

    public Set<String> liveMutableIoPaths() {
        return descriptors().liveMutablePaths();
    }

    public Set<String> disabledOnlyIoPaths() {
        return descriptors().disabledOnlyPaths();
    }

    private WorkMutationDescriptors descriptors() {
        Set<String> live = Stream.concat(
                inputPolicies.stream().flatMap(policy -> policy.descriptors().liveMutablePaths().stream()),
                outputPolicies.stream().flatMap(policy -> policy.descriptors().liveMutablePaths().stream()))
            .collect(Collectors.toUnmodifiableSet());
        Set<String> disabledOnly = Stream.concat(
                inputPolicies.stream().flatMap(policy -> policy.descriptors().disabledOnlyPaths().stream()),
                outputPolicies.stream().flatMap(policy -> policy.descriptors().disabledOnlyPaths().stream()))
            .collect(Collectors.toUnmodifiableSet());
        return new WorkMutationDescriptors(live, disabledOnly);
    }

    private static <T> T exactlyOne(List<T> policies, String direction, Object type) {
        if (policies.size() != 1) throw new IllegalStateException("Expected exactly one " + direction
            + " mutation policy for type '" + type + "' but found " + policies.size());
        return policies.getFirst();
    }

    private void validateInputPolicy(WorkInputMutationPolicy policy) {
        Objects.requireNonNull(policy, "inputPolicies entry");
        validate(policy.type(), policy.descriptors(), "inputs.");
    }

    private void validateOutputPolicy(WorkOutputMutationPolicy policy) {
        Objects.requireNonNull(policy, "outputPolicies entry");
        validate(policy.type(), policy.descriptors(), "outputs.");
    }

    private static void validate(Object type, WorkMutationDescriptors descriptors, String prefix) {
        Objects.requireNonNull(type, "policy.type()");
        Objects.requireNonNull(descriptors, "policy.descriptors()");
        if (!descriptors.liveMutablePaths().stream().allMatch(path -> path.startsWith(prefix))
            || !descriptors.disabledOnlyPaths().stream().allMatch(path -> path.startsWith(prefix))) {
            throw new IllegalArgumentException("Mutation policy descriptors must use '" + prefix + "' paths");
        }
    }
}
