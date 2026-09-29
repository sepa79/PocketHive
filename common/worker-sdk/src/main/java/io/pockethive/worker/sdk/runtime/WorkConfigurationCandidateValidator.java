package io.pockethive.worker.sdk.runtime;

import io.pockethive.work.config.WorkDeliveryParser;

import io.pockethive.work.config.WorkConfigurationException;
import io.pockethive.work.config.WorkConfigurationFields;
import io.pockethive.work.config.WorkConfigurationMode;
import io.pockethive.work.config.WorkConfigurationParser;
import io.pockethive.work.config.WorkConfigurationProblem;
import io.pockethive.work.config.WorkerOutputType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Responsibility: build and validate one immutable complete Work IO candidate before state acceptance.
 * Must not: merge domain configuration, mutate source maps, apply adapters or acknowledge control commands.
 * Contract: RESP-WORK-STATE — docs/architecture/runtime-responsibilities.md#resp-work-state.
 */
public final class WorkConfigurationCandidateValidator {
    private final WorkConfigurationParser parser;

    public WorkConfigurationCandidateValidator(WorkConfigurationParser parser) {
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    public void validate(WorkerState state, Map<String, Object> mergedRaw) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(mergedRaw, "mergedRaw");
        if (!mergedRaw.containsKey(WorkConfigurationFields.INPUTS)
            && !mergedRaw.containsKey(WorkConfigurationFields.OUTPUTS)) return;
        var candidate = new LinkedHashMap<String, Object>();
        candidate.put(WorkConfigurationFields.INPUTS, inputCandidate(state, mergedRaw));
        candidate.put(WorkConfigurationFields.OUTPUTS, outputCandidate(state, mergedRaw));
        var validation = parser.validate(java.util.Collections.unmodifiableMap(candidate), WorkConfigurationMode.RESOLVED);
        if (!validation.problems().isEmpty()) throw new WorkConfigurationException(validation.problems());
        if (!validation.deferredPaths().isEmpty()) {
            throw new WorkConfigurationException(List.of(new WorkConfigurationProblem(WorkConfigurationFields.INPUTS,
                "Resolved Work configuration must not contain deferred paths: " + validation.deferredPaths())));
        }
        var configuration = validation.configuration();
        if (!configuration.outputDelivery().equals(state.definition().io().outputDelivery())) {
            throw new WorkConfigurationException(List.of(new WorkConfigurationProblem(
                WorkDeliveryParser.PATH,
                "Delivery policy is fixed at startup; restart the worker/swarm to change it.")));
        }
        if (!configuration.inputType().equals(state.definition().input())
            || !configuration.outputType().equals(state.definition().outputType())) {
            throw new WorkConfigurationException(List.of(new WorkConfigurationProblem(WorkConfigurationFields.path(
                WorkConfigurationFields.INPUTS, WorkConfigurationFields.TYPE),
                "Validated Work IO types must match the worker definition.")));
        }
    }

    private static Map<String, Object> inputCandidate(WorkerState state, Map<String, Object> mergedRaw) {
        Object rawInputs = mergedRaw.get(WorkConfigurationFields.INPUTS);
        if (mergedRaw.containsKey(WorkConfigurationFields.INPUTS) && !(rawInputs instanceof Map<?, ?>)) return null;
        var fields = copy(rawInputs);
        fields.putIfAbsent(WorkConfigurationFields.TYPE, state.definition().input().name());
        Object rawSettings = fields.get(state.definition().input().settingsKey());
        if (rawSettings instanceof Map<?, ?>) {
            var settings = new LinkedHashMap<String, Object>(state.inputStartup());
            ((Map<?, ?>) rawSettings).forEach((key, value) -> settings.put(String.valueOf(key), value));
            fields.put(state.definition().input().settingsKey(), java.util.Collections.unmodifiableMap(settings));
        } else if (!fields.containsKey(state.definition().input().settingsKey()) && !state.inputStartup().isEmpty()) {
            fields.put(state.definition().input().settingsKey(), java.util.Collections.unmodifiableMap(
                new LinkedHashMap<>(state.inputStartup())));
        }
        return java.util.Collections.unmodifiableMap(fields);
    }

    private static Map<String, Object> outputCandidate(WorkerState state, Map<String, Object> mergedRaw) {
        Object rawOutputs = mergedRaw.get(WorkConfigurationFields.OUTPUTS);
        if (mergedRaw.containsKey(WorkConfigurationFields.OUTPUTS) && !(rawOutputs instanceof Map<?, ?>)) return null;
        var fields = copy(rawOutputs);
        fields.putIfAbsent(WorkConfigurationFields.TYPE, state.definition().outputType().name());
        if (state.definition().outputType() == WorkerOutputType.NONE) return java.util.Collections.unmodifiableMap(fields);
        return java.util.Collections.unmodifiableMap(fields);
    }

    private static LinkedHashMap<String, Object> copy(Object raw) {
        var fields = new LinkedHashMap<String, Object>();
        if (raw instanceof Map<?, ?> map) map.forEach((key, value) -> fields.put(String.valueOf(key), value));
        return fields;
    }
}
