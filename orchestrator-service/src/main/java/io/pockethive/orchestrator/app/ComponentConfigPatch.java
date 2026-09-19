package io.pockethive.orchestrator.app;

import io.pockethive.orchestrator.domain.SwarmOperationCoordinator.ConfigEnabledExpectation;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;

final class ComponentConfigPatch {
    private ComponentConfigPatch() {
    }

    static Map<String, Object> normalizeForUpdate(Map<String, Object> patch) {
        if (patch == null || patch.isEmpty()) {
            return null;
        }
        validate(patch);
        return immutableCopy(patch);
    }

    static Map<String, Object> requireForPreview(Map<String, Object> patch) {
        if (patch == null || patch.isEmpty()) {
            throw new IllegalArgumentException("patch must not be empty");
        }
        validate(patch);
        return immutableCopy(patch);
    }

    static ConfigEnabledExpectation enabledExpectation(Map<String, Object> patch) {
        if (patch == null || !patch.containsKey("enabled")) {
            return ConfigEnabledExpectation.UNCHANGED;
        }
        return ConfigEnabledExpectation.fromRequested((Boolean) patch.get("enabled"));
    }

    static Map<String, Object> shallowMerge(Map<String, Object> current, Map<String, Object> patch) {
        Map<String, Object> result = new LinkedHashMap<>(current);
        result.putAll(patch);
        return Collections.unmodifiableMap(result);
    }

    private static void validate(Map<String, Object> patch) {
        if (patch.containsKey("enabled") && !(patch.get("enabled") instanceof Boolean)) {
            throw new IllegalArgumentException("patch.enabled must be a boolean");
        }
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
