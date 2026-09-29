package io.pockethive.manager.ports;

import io.pockethive.manager.runtime.RuntimeInspection;

/**
 * Responsibility: provide normalized runtime diagnostics.
 * Must not: expose SDK types or decide request authorization.
 * Contract: RESP-DOCKER-RUNTIME — docs/architecture/runtime-responsibilities.md#resp-docker-runtime.
 */
public interface ComputeRuntimeDebugPort {
    RuntimeInspection inspect(String runtimeId);

    String logs(String runtimeId, int tailLines, Integer sinceEpochSeconds);
}
