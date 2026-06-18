package io.pockethive.orchestrator.runtime;

import java.util.Map;

public final class RuntimeDebugPorts {
    private RuntimeDebugPorts() {
    }

    public interface ComputeRuntimeDebugPort {
        Map<String, Object> inspect(String computeAdapter, String runtimeId);

        String logs(String computeAdapter, String runtimeId, int tailLines, Integer sinceEpochSeconds);
    }
}
