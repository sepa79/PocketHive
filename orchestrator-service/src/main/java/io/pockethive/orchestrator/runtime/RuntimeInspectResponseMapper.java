package io.pockethive.orchestrator.runtime;

import io.pockethive.docker.compute.PocketHiveDockerLabels;
import io.pockethive.manager.runtime.RuntimeInspection;
import io.pockethive.manager.runtime.RuntimeMountInspection;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.RuntimeInspectResponse;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.RuntimeTarget;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Responsibility: construct the existing REST inspection response and redact host sources.
 * Must not: interpret Docker fields or authorize target selection.
 * Contract: RESP-DOCKER-RUNTIME — docs/architecture/runtime-responsibilities.md#resp-docker-runtime.
 */
final class RuntimeInspectResponseMapper {
    private static final String MOUNT_TYPE_VOLUME = "volume";
    private static final String REDACTED = "[REDACTED]";

    private RuntimeInspectResponseMapper() {
    }

    static RuntimeInspectResponse map(RuntimeTarget target, RuntimeInspection inspection) {
        var state = inspection.state();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", state.status());
        summary.put("running", state.running());
        summary.put("exitCode", state.exitCode());
        summary.put("error", state.error());
        summary.put("health", state.health());
        summary.put("startedAt", state.startedAt());
        summary.put("finishedAt", state.finishedAt());
        return new RuntimeInspectResponse(target,
            Map.of("available", true, "owner", PocketHiveDockerLabels.OWNER_ORCHESTRATOR), summary,
            inspection.createdAt(), inspection.restartCount(), inspection.restartPolicy(),
            inspection.mounts().stream().map(RuntimeInspectResponseMapper::mount).toList(), inspection.networks());
    }

    private static Map<String, Object> mount(RuntimeMountInspection mount) {
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("type", mount.type());
        safe.put("name", mount.name());
        safe.put("destination", mount.destination());
        safe.put("mode", mount.mode());
        safe.put("rw", mount.writable());
        if (mount.reportsPropagation()) {
            safe.put("propagation", mount.propagation());
        }
        safe.put("source", MOUNT_TYPE_VOLUME.equalsIgnoreCase(mount.type()) || mount.name() != null
            ? mount.source() : mount.source() == null ? null : REDACTED);
        return safe;
    }
}
