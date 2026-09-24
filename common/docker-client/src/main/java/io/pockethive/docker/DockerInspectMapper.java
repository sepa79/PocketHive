package io.pockethive.docker;

import io.pockethive.manager.runtime.RuntimeInspection;
import io.pockethive.manager.runtime.RuntimeInspectionState;
import io.pockethive.manager.runtime.RuntimeMountInspection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Responsibility: normalize Docker inspect data into runtime diagnostic values.
 * Must not: redact application responses or infer lifecycle completion.
 * Contract: RESP-DOCKER-RUNTIME — docs/architecture/runtime-responsibilities.md#resp-docker-runtime.
 */
final class DockerInspectMapper {
    private static final String MOUNT_TYPE_VOLUME = "volume";

    private DockerInspectMapper() {
    }

    static RuntimeInspection map(DockerRuntimeKind kind, Map<String, Object> raw) {
        return switch (kind) {
            case CONTAINER -> container(raw);
            case SERVICE -> service(raw);
        };
    }

    private static RuntimeInspection container(Map<String, Object> raw) {
        Map<String, Object> state = map(value(raw, "State", "state"));
        Map<String, Object> health = map(value(state, "Health", "health"));
        Map<String, Object> hostConfig = map(value(raw, "HostConfig", "hostConfig"));
        Map<String, Object> restartPolicy = map(value(hostConfig, "RestartPolicy", "restartPolicy"));
        Map<String, Object> networkSettings = map(value(raw, "NetworkSettings", "networkSettings"));
        Map<String, Object> networks = map(value(networkSettings, "Networks", "networks"));
        return new RuntimeInspection(new RuntimeInspectionState(
            text(state, "Status", "status"), value(state, "Running", "running"),
            value(state, "ExitCode", "exitCode", "ExitCodeLong", "exitCodeLong"),
            emptyToNull(text(state, "Error", "error")), text(health, "Status", "status"),
            text(state, "StartedAt", "startedAt"), text(state, "FinishedAt", "finishedAt")),
            text(raw, "Created", "created"), integer(value(raw, "RestartCount", "restartCount")),
            text(restartPolicy, "Name", "name"),
            listOfMaps(value(raw, "Mounts", "mounts")).stream().map(DockerInspectMapper::containerMount).toList(),
            networks.keySet().stream().map(String::valueOf).sorted().toList());
    }

    private static RuntimeInspection service(Map<String, Object> raw) {
        Map<String, Object> spec = map(value(raw, "Spec", "spec"));
        Map<String, Object> taskTemplate = map(value(spec, "TaskTemplate", "taskTemplate"));
        Map<String, Object> containerSpec = map(value(taskTemplate, "ContainerSpec", "containerSpec"));
        Map<String, Object> restartPolicy = map(value(taskTemplate, "RestartPolicy", "restartPolicy"));
        return new RuntimeInspection(new RuntimeInspectionState(
            DockerRuntimeKind.SERVICE.stateLabel(), true, null, null, null, null, null),
            text(raw, "CreatedAt", "createdAt"), null, text(restartPolicy, "Condition", "condition"),
            listOfMaps(value(containerSpec, "Mounts", "mounts")).stream().map(DockerInspectMapper::serviceMount).toList(),
            serviceNetworks(spec, taskTemplate));
    }

    private static RuntimeMountInspection containerMount(Map<String, Object> mount) {
        // Preserve the existing diagnostic RW calculation; changing it requires a separate behavior decision.
        Object writable = value(mount, "RW", "rw", "ReadOnly", "readOnly") instanceof Boolean readOnly
            ? !readOnly : value(mount, "RW", "rw");
        return new RuntimeMountInspection(text(mount, "Type", "type"), text(mount, "Name", "name"),
            text(mount, "Source", "source"),
            firstText(text(mount, "Destination", "destination"), text(mount, "Target", "target")),
            text(mount, "Mode", "mode"), writable, text(mount, "Propagation", "propagation"), true);
    }

    private static RuntimeMountInspection serviceMount(Map<String, Object> mount) {
        String type = text(mount, "Type", "type");
        String source = text(mount, "Source", "source");
        Boolean readOnly = bool(value(mount, "ReadOnly", "readOnly"));
        return new RuntimeMountInspection(type, MOUNT_TYPE_VOLUME.equalsIgnoreCase(type) ? source : null,
            source, text(mount, "Target", "target"), readOnly == null ? null : readOnly ? "ro" : "rw",
            readOnly == null ? null : !readOnly, null, false);
    }

    private static List<String> serviceNetworks(Map<String, Object> spec, Map<String, Object> taskTemplate) {
        List<Map<String, Object>> networks = listOfMaps(value(taskTemplate, "Networks", "networks"));
        if (networks.isEmpty()) {
            networks = listOfMaps(value(spec, "Networks", "networks"));
        }
        return networks.stream()
            .map(network -> firstText(
                text(network, "Target", "target"),
                text(network, "NetworkID", "networkID", "networkId"),
                text(network, "Name", "name")))
            .filter(Objects::nonNull)
            .sorted()
            .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.of();
    }

    private static List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> maps = new ArrayList<>();
        for (Object item : list) {
            maps.add(map(item));
        }
        return maps;
    }

    private static Object value(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
    }

    private static String text(Map<String, Object> map, String... keys) {
        Object value = value(map, keys);
        return value == null ? null : optionalText(String.valueOf(value));
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = optionalText(value == null ? null : String.valueOf(value));
        if (text == null) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Boolean bool(Object value) {
        return value instanceof Boolean booleanValue ? booleanValue : null;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String firstText(String... values) {
        for (String value : values) {
            String text = optionalText(value);
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    private static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
