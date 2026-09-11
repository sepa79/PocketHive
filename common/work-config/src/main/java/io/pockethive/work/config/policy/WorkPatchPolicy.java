package io.pockethive.work.config.policy;

import io.pockethive.work.config.WorkConfigurationException;
import io.pockethive.work.config.WorkConfigurationFields;
import io.pockethive.work.config.WorkInputMutationPolicy;
import io.pockethive.work.config.WorkMutationDescriptors;
import io.pockethive.work.config.WorkMutationRequest;
import io.pockethive.work.config.WorkOutputMutationPolicy;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Responsibility: validate outer IO patch structure and delegate selected adapter field semantics.
 * Must not: own adapter mutability rules, parse adapter settings or write accepted state.
 * Contract: RESP-WORK-PATCH-POLICY — docs/architecture/runtime-responsibilities.md#resp-work-patch-policy.
 */
public final class WorkPatchPolicy {
    private static final String INPUTS_PREFIX = WorkConfigurationFields.INPUTS + ".";
    private static final String OUTPUTS_PREFIX = WorkConfigurationFields.OUTPUTS + ".";

    private final String workerName;
    private final WorkInputMutationPolicy inputPolicy;
    private final Map<String, Object> inputStartupSettings;
    private final WorkOutputMutationPolicy outputPolicy;
    private final Map<String, Object> outputStartupSettings;

    public WorkPatchPolicy(String workerName,
                           WorkInputMutationPolicy inputPolicy,
                           Map<String, Object> inputStartupSettings,
                           WorkOutputMutationPolicy outputPolicy,
                           Map<String, Object> outputStartupSettings) {
        this.workerName = Objects.requireNonNull(workerName, "workerName");
        this.inputPolicy = requireInputPolicy(inputPolicy);
        this.inputStartupSettings = Map.copyOf(Objects.requireNonNull(inputStartupSettings, "inputStartupSettings"));
        this.outputPolicy = requireOutputPolicy(outputPolicy);
        this.outputStartupSettings = Map.copyOf(Objects.requireNonNull(outputStartupSettings, "outputStartupSettings"));
    }

    public boolean isLiveMutableIoPath(String path) {
        return descriptorsFor(path).liveMutablePaths().contains(path);
    }

    public Set<String> liveMutableIoPaths() {
        return union(inputPolicy.descriptors().liveMutablePaths(), outputPolicy.descriptors().liveMutablePaths());
    }

    public boolean isDisabledOnlyIoPath(String path) {
        return descriptorsFor(path).disabledOnlyPaths().contains(path);
    }

    public Set<String> disabledOnlyIoPaths() {
        return union(inputPolicy.descriptors().disabledOnlyPaths(), outputPolicy.descriptors().disabledOnlyPaths());
    }

    public static boolean isIoPath(String path) {
        return path != null && (path.startsWith(INPUTS_PREFIX) || path.startsWith(OUTPUTS_PREFIX));
    }

    public void validate(Map<String, Object> previousRaw, Map<String, Object> update, boolean workerEnabled) {
        Objects.requireNonNull(update, "update");
        var unsupported = new InputLifecyclePolicy().configurationProblems(
            update.get(WorkConfigurationFields.INPUTS), WorkConfigurationFields.INPUTS);
        if (!unsupported.isEmpty()) throw new WorkConfigurationException(unsupported);
        if (update.isEmpty()) return;
        Map<String, Object> previous = Objects.requireNonNull(previousRaw, "previousRaw");
        boolean bootstrap = previous.isEmpty();
        validateInputRoot(previous, update, bootstrap, workerEnabled);
        validateOutputRoot(previous, update, bootstrap, workerEnabled);
    }

    public void validateReset(Map<String, Object> previousRaw) {
        Objects.requireNonNull(previousRaw, "previousRaw");
        if (previousRaw.isEmpty()) return;
        if (previousRaw.containsKey(WorkConfigurationFields.INPUTS)) {
            throw unsafeUpdate(WorkConfigurationFields.INPUTS);
        }
        if (previousRaw.containsKey(WorkConfigurationFields.OUTPUTS)) {
            throw unsafeUpdate(WorkConfigurationFields.OUTPUTS);
        }
    }

    private void validateInputRoot(Map<String, Object> previous, Map<String, Object> update,
                                   boolean bootstrap, boolean workerEnabled) {
        validateRoot(WorkConfigurationFields.INPUTS, previous, update, bootstrap, workerEnabled,
            inputPolicy.type().settingsKey(),
            inputPolicy.descriptors(), inputStartupSettings, request -> inputPolicy.validate(request));
    }

    private void validateOutputRoot(Map<String, Object> previous, Map<String, Object> update,
                                    boolean bootstrap, boolean workerEnabled) {
        validateRoot(WorkConfigurationFields.OUTPUTS, previous, update, bootstrap, workerEnabled,
            outputPolicy.type().settingsKey(),
            outputPolicy.descriptors(), outputStartupSettings, request -> outputPolicy.validate(request));
    }

    private void validateRoot(String root, Map<String, Object> previous, Map<String, Object> update,
                              boolean bootstrap, boolean workerEnabled, String selectedSettingsKey,
                              WorkMutationDescriptors descriptors, Map<String, Object> startupSettings,
                              MutationValidator validator) {
        Object raw = update.get(root);
        if (raw == null) return;
        if (!(raw instanceof Map<?, ?> fields)) throw unsafeUpdate(root);
        for (var entry : fields.entrySet()) {
            if (entry.getKey() == null) continue;
            String key = entry.getKey().toString();
            String path = root + "." + key;
            if (WorkConfigurationFields.TYPE.equals(key)) {
                if (!bootstrap) rejectIfChanged(previous, path, entry.getValue());
            } else if (!(entry.getValue() instanceof Map<?, ?> nested)) {
                throw unsafeUpdate(path);
            } else {
                validateSubblock(previous, update, path, nested, bootstrap, workerEnabled, selectedSettingsKey,
                    descriptors, startupSettings, validator);
            }
        }
    }

    private void validateSubblock(Map<String, Object> previous, Map<String, Object> update, String subblockPath,
                                  Map<?, ?> patch, boolean bootstrap, boolean workerEnabled, String selectedSettingsKey,
                                  WorkMutationDescriptors descriptors, Map<String, Object> startupSettings,
                                  MutationValidator validator) {
        for (var entry : patch.entrySet()) {
            if (entry.getKey() == null) continue;
            String path = subblockPath + "." + entry.getKey();
            if (subblockPath.endsWith("." + selectedSettingsKey) && descriptors.liveMutablePaths().contains(path)) {
                validateMutable(previous, subblockPath, path, entry.getValue(), patch, workerEnabled,
                    descriptors, startupSettings, validator);
            } else if (!bootstrap) {
                rejectIfChanged(previous, path, entry.getValue());
            }
        }
    }

    private void validateMutable(Map<String, Object> previous, String subblockPath, String path, Object value,
                                 Map<?, ?> patch, boolean workerEnabled, WorkMutationDescriptors descriptors,
                                 Map<String, Object> startupSettings, MutationValidator validator) {
        Object prior = valueAt(previous, path);
        if (Objects.equals(prior, value)) return;
        if (descriptors.disabledOnlyPaths().contains(path) && workerEnabled) {
            throw new IllegalStateException("Runtime config-update cannot change disabled-only IO field '" + path
                + "' for enabled worker '" + workerName + "'; stop the swarm first.");
        }
        validator.validate(new WorkMutationRequest(workerName, startupSettings,
            settingsAt(previous, subblockPath), stringMap(patch), path, prior, value, workerEnabled));
    }

    private WorkMutationDescriptors descriptorsFor(String path) {
        if (path != null && path.startsWith(INPUTS_PREFIX)) return inputPolicy.descriptors();
        if (path != null && path.startsWith(OUTPUTS_PREFIX)) return outputPolicy.descriptors();
        return new WorkMutationDescriptors(Set.of(), Set.of());
    }

    private static WorkInputMutationPolicy requireInputPolicy(WorkInputMutationPolicy policy) {
        Objects.requireNonNull(policy, "inputPolicy");
        validatePolicy(policy.type(), policy.descriptors(), INPUTS_PREFIX);
        return policy;
    }

    private static WorkOutputMutationPolicy requireOutputPolicy(WorkOutputMutationPolicy policy) {
        Objects.requireNonNull(policy, "outputPolicy");
        validatePolicy(policy.type(), policy.descriptors(), OUTPUTS_PREFIX);
        return policy;
    }

    private static void validatePolicy(Object type, WorkMutationDescriptors descriptors, String prefix) {
        Objects.requireNonNull(type, "policy.type()");
        Objects.requireNonNull(descriptors, "policy.descriptors()");
        if (!descriptors.liveMutablePaths().stream().allMatch(path -> path.startsWith(prefix))
            || !descriptors.disabledOnlyPaths().stream().allMatch(path -> path.startsWith(prefix))) {
            throw new IllegalArgumentException("Mutation policy descriptors must use '" + prefix + "' paths");
        }
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        return Stream.concat(first.stream(), second.stream()).collect(Collectors.toUnmodifiableSet());
    }

    private void rejectIfChanged(Map<String, Object> previous, String path, Object value) {
        if (!Objects.equals(valueAt(previous, path), value)) throw unsafeUpdate(path);
    }

    private static Object valueAt(Map<String, Object> source, String path) {
        Object current = source;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(segment)) return null;
            current = map.get(segment);
        }
        return current;
    }

    private static Map<String, Object> settingsAt(Map<String, Object> source, String settingsPath) {
        Object settings = valueAt(source, settingsPath);
        return settings instanceof Map<?, ?> map ? stringMap(map) : Map.of();
    }

    private static Map<String, Object> stringMap(Map<?, ?> source) {
        var copy = new java.util.LinkedHashMap<String, Object>();
        source.forEach((key, value) -> copy.put(Objects.toString(key), value));
        return copy;
    }

    private IllegalStateException unsafeUpdate(String path) {
        return new IllegalStateException("Runtime config-update cannot change unsafe IO field '" + path
            + "' for worker '" + workerName + "'; restart the worker/swarm to change input or output wiring.");
    }

    @FunctionalInterface
    private interface MutationValidator {
        void validate(WorkMutationRequest request);
    }
}
