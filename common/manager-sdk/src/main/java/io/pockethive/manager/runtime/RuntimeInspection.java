package io.pockethive.manager.runtime;

import java.util.List;
import java.util.Objects;

/**
 * Responsibility: carry an immutable normalized runtime inspection.
 * Must not: expose raw infrastructure responses or construct HTTP responses.
 * Contract: RESP-DOCKER-RUNTIME — docs/architecture/runtime-responsibilities.md#resp-docker-runtime.
 */
public record RuntimeInspection(RuntimeInspectionState state, String createdAt, Integer restartCount,
                                String restartPolicy, List<RuntimeMountInspection> mounts, List<String> networks) {
    public RuntimeInspection {
        Objects.requireNonNull(state, "state");
        mounts = List.copyOf(mounts);
        networks = List.copyOf(networks);
    }
}
