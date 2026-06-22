package io.pockethive.orchestrator.runtime;

import io.pockethive.docker.compute.PocketHiveDockerLabels;
import io.pockethive.manager.ports.ComputeAdapter;
import io.pockethive.manager.runtime.ComputeAdapterType;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.ComputeRuntimeInventoryPort;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.ComputeRuntimeResource;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.BlockedResource;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.Counts;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.ResourceListRequest;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.ResourceListResponse;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.RuntimeEntry;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.RuntimeInspectResponse;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.RuntimeLogsRequest;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.RuntimeLogsResponse;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.RuntimeTarget;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.RuntimeTargetRequest;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.RuntimeVersionResponse;
import io.pockethive.orchestrator.runtime.RuntimeDebugPorts.ComputeRuntimeDebugPort;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class RuntimeDebugService {
    private static final String INSPECT_SOURCE_OWNER = "owner";
    private static final String MOUNT_TYPE_VOLUME = "volume";
    private static final String REDACTED = "[REDACTED]";
    private static final String VERSION_SOURCE_IMAGE_TAG = "imageTag";
    private static final int DEFAULT_TAIL_LINES = 200;
    private static final int MAX_TAIL_LINES = 2000;
    private static final Pattern AUTHORIZATION_PATTERN =
        Pattern.compile("\\b(Authorization:\\s*(?:Bearer|Basic)\\s+)\\S+", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET_PATTERN = Pattern.compile(
        "\\b((?:password|passwd|pwd|token|secret|api[_-]?key|access[_-]?key)\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s,;]+)",
        Pattern.CASE_INSENSITIVE);
    private static final List<String> REQUIRED_LABELS = List.of(
        PocketHiveDockerLabels.MANAGED,
        PocketHiveDockerLabels.SWARM_ID,
        PocketHiveDockerLabels.RUN_ID,
        PocketHiveDockerLabels.RESOURCE_KIND,
        PocketHiveDockerLabels.ROLE,
        PocketHiveDockerLabels.INSTANCE);

    private final ComputeRuntimeInventoryPort inventory;
    private final ComputeRuntimeDebugPort debugPort;
    private final ComputeAdapter computeAdapter;

    public RuntimeDebugService(ComputeRuntimeInventoryPort inventory,
                               ComputeRuntimeDebugPort debugPort,
                               ComputeAdapter computeAdapter) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.debugPort = Objects.requireNonNull(debugPort, "debugPort");
        this.computeAdapter = Objects.requireNonNull(computeAdapter, "computeAdapter");
    }

    public ResourceListResponse list(ResourceListRequest request) {
        requireRequest(request);
        String adapterType = adapterType();
        String swarmId = requireText(request.swarmId(), "swarmId");
        String runId = optionalText(request.runId());
        boolean includeManagers = request.includeManagers() == null || request.includeManagers();
        List<RuntimeEntry> workers = new ArrayList<>();
        List<RuntimeEntry> managers = new ArrayList<>();
        List<BlockedResource> blocked = new ArrayList<>();

        for (ComputeRuntimeResource resource : inventory.list()) {
            Map<String, String> labels = labels(resource);
            if (!swarmId.equals(labels.get(PocketHiveDockerLabels.SWARM_ID))) {
                continue;
            }
            if (!PocketHiveDockerLabels.MANAGED_VALUE.equals(labels.get(PocketHiveDockerLabels.MANAGED))) {
                blocked.add(blocked(resource, "missing pockethive.managed=true"));
                continue;
            }
            List<String> missing = missingLabels(labels);
            if (!missing.isEmpty()) {
                blocked.add(blocked(resource, "missing required labels: " + String.join(", ", missing)));
                continue;
            }
            if (runId != null && !runId.equals(labels.get(PocketHiveDockerLabels.RUN_ID))) {
                continue;
            }

            RuntimeEntry entry = entry(resource);
            if (PocketHiveDockerLabels.RESOURCE_KIND_WORKER.equals(entry.resourceKind())) {
                workers.add(entry);
            } else if (PocketHiveDockerLabels.RESOURCE_KIND_MANAGER.equals(entry.resourceKind())) {
                if (includeManagers) {
                    managers.add(entry);
                }
            } else {
                blocked.add(blocked(resource, "unsupported pockethive.resourceKind=" + entry.resourceKind()));
            }
        }

        workers.sort(RuntimeDebugService::compareEntry);
        managers.sort(RuntimeDebugService::compareEntry);
        blocked.sort(RuntimeDebugService::compareBlocked);
        return new ResourceListResponse(
            adapterType,
            swarmId,
            runId,
            new Counts(workers.size(), managers.size(), blocked.size()),
            List.copyOf(workers),
            List.copyOf(managers),
            List.copyOf(blocked));
    }

    public RuntimeLogsResponse logs(RuntimeLogsRequest request) {
        requireRequest(request);
        RuntimeTarget target = target(new RuntimeTargetRequest(
            request.swarmId(),
            request.runId(),
            request.runtimeId(),
            request.instance(),
            request.role(),
            request.resourceKind()));
        int tailLines = normalizeTailLines(request.tailLines());
        Integer sinceEpochSeconds = sinceEpochSeconds(request.since());
        String rawLogs = debugPort.logs(target.runtimeId(), tailLines, sinceEpochSeconds);
        String logs = redact(rawLogs);
        return new RuntimeLogsResponse(
            target,
            tailLines,
            optionalText(request.since()),
            true,
            countLines(logs),
            logs);
    }

    public RuntimeVersionResponse version(RuntimeTargetRequest request) {
        requireRequest(request);
        RuntimeTarget target = target(request);
        ImageReference image = parseImageReference(target.image());
        String declaredVersion = optionalText(target.labels().get(PocketHiveDockerLabels.VERSION));
        String reportedVersion = declaredVersion != null ? declaredVersion : image.imageTag();
        return new RuntimeVersionResponse(
            target,
            declaredVersion,
            image.image(),
            image.imageTag(),
            image.imageDigest(),
            reportedVersion,
            declaredVersion != null ? PocketHiveDockerLabels.VERSION : image.imageTag() != null ? VERSION_SOURCE_IMAGE_TAG : null);
    }

    public RuntimeInspectResponse inspect(RuntimeTargetRequest request) {
        requireRequest(request);
        RuntimeTarget target = target(request);
        Map<String, Object> raw = debugPort.inspect(target.runtimeId());
        Map<String, Object> source = Map.of(
            "available", true,
            INSPECT_SOURCE_OWNER, PocketHiveDockerLabels.OWNER_ORCHESTRATOR);
        if (RuntimeCleanupPorts.RUNTIME_TYPE_SERVICE.equals(target.runtimeType())) {
            return serviceInspect(target, source, raw);
        }
        return containerInspect(target, source, raw);
    }

    private RuntimeTarget target(RuntimeTargetRequest request) {
        String swarmId = requireText(request.swarmId(), "swarmId");
        String runId = optionalText(request.runId());
        String runtimeId = optionalText(request.runtimeId());
        String instance = optionalText(request.instance());
        String role = optionalText(request.role());
        String resourceKind = optionalText(request.resourceKind());
        if (resourceKind == null) {
            resourceKind = PocketHiveDockerLabels.RESOURCE_KIND_WORKER;
        }
        if (!PocketHiveDockerLabels.RESOURCE_KIND_WORKER.equals(resourceKind)
            && !PocketHiveDockerLabels.RESOURCE_KIND_MANAGER.equals(resourceKind)) {
            throw error(HttpStatus.BAD_REQUEST, "resourceKind must be worker or manager");
        }
        if (runtimeId == null && instance == null && role == null) {
            throw error(HttpStatus.BAD_REQUEST, "runtimeId, instance, or role must identify a runtime debug target");
        }

        List<RuntimeTarget> matches = new ArrayList<>();
        for (ComputeRuntimeResource resource : inventory.list()) {
            Map<String, String> labels = labels(resource);
            if (!PocketHiveDockerLabels.MANAGED_VALUE.equals(labels.get(PocketHiveDockerLabels.MANAGED))) {
                continue;
            }
            if (!swarmId.equals(labels.get(PocketHiveDockerLabels.SWARM_ID))) {
                continue;
            }
            if (runId != null && !runId.equals(labels.get(PocketHiveDockerLabels.RUN_ID))) {
                continue;
            }
            if (!resourceKind.equals(labels.get(PocketHiveDockerLabels.RESOURCE_KIND))) {
                continue;
            }
            if (runtimeId != null && !runtimeId.equals(resource.runtimeId())) {
                continue;
            }
            if (instance != null && !instance.equals(labels.get(PocketHiveDockerLabels.INSTANCE))) {
                continue;
            }
            if (role != null && !role.equals(labels.get(PocketHiveDockerLabels.ROLE))) {
                continue;
            }
            List<String> missing = missingLabels(labels);
            if (!missing.isEmpty()) {
                throw error(HttpStatus.CONFLICT,
                    "runtime debug target '" + resource.runtimeId() + "' is missing required labels: "
                        + String.join(", ", missing));
            }
            matches.add(target(resource));
        }
        matches.sort(RuntimeDebugService::compareTarget);
        if (matches.isEmpty()) {
            throw error(HttpStatus.NOT_FOUND, "no matching PocketHive runtime debug target was found");
        }
        if (matches.size() > 1) {
            String ids = String.join(", ", matches.stream().map(RuntimeTarget::runtimeId).toList());
            throw error(HttpStatus.CONFLICT, "runtime debug target is ambiguous; provide runtimeId or instance. Matches: " + ids);
        }
        return matches.get(0);
    }

    private RuntimeTarget target(ComputeRuntimeResource resource) {
        Map<String, String> labels = labels(resource);
        return new RuntimeTarget(
            resource.runtimeId(),
            resource.runtimeType(),
            resource.name(),
            labels.get(PocketHiveDockerLabels.RESOURCE_KIND),
            labels.get(PocketHiveDockerLabels.SWARM_ID),
            labels.get(PocketHiveDockerLabels.RUN_ID),
            labels.get(PocketHiveDockerLabels.ROLE),
            labels.get(PocketHiveDockerLabels.INSTANCE),
            labels.get(PocketHiveDockerLabels.LOGICAL_NAME),
            resource.state(),
            firstText(resource.image(), labels.get(PocketHiveDockerLabels.IMAGE)),
            pockethiveLabelsOnly(labels));
    }

    private RuntimeEntry entry(ComputeRuntimeResource resource) {
        Map<String, String> labels = labels(resource);
        String imageValue = firstText(resource.image(), labels.get(PocketHiveDockerLabels.IMAGE));
        ImageReference image = parseImageReference(imageValue);
        String declaredVersion = optionalText(labels.get(PocketHiveDockerLabels.VERSION));
        return new RuntimeEntry(
            resource.runtimeId(),
            resource.runtimeType(),
            resource.name(),
            labels.get(PocketHiveDockerLabels.RESOURCE_KIND),
            labels.get(PocketHiveDockerLabels.SWARM_ID),
            labels.get(PocketHiveDockerLabels.RUN_ID),
            labels.get(PocketHiveDockerLabels.ROLE),
            labels.get(PocketHiveDockerLabels.INSTANCE),
            labels.get(PocketHiveDockerLabels.LOGICAL_NAME),
            resource.state(),
            isRunning(resource),
            image.image(),
            image.imageTag(),
            image.imageDigest(),
            declaredVersion,
            declaredVersion != null ? declaredVersion : image.imageTag(),
            resource.createdAt(),
            resource.startedAt(),
            resource.finishedAt(),
            "unknown",
            pockethiveLabelsOnly(labels));
    }

    private RuntimeInspectResponse containerInspect(RuntimeTarget target, Map<String, Object> source, Map<String, Object> raw) {
        Map<String, Object> state = map(value(raw, "State", "state"));
        Map<String, Object> health = map(value(state, "Health", "health"));
        Map<String, Object> hostConfig = map(value(raw, "HostConfig", "hostConfig"));
        Map<String, Object> restartPolicy = map(value(hostConfig, "RestartPolicy", "restartPolicy"));
        Map<String, Object> networkSettings = map(value(raw, "NetworkSettings", "networkSettings"));
        Map<String, Object> networks = map(value(networkSettings, "Networks", "networks"));
        Map<String, Object> stateSummary = new LinkedHashMap<>();
        stateSummary.put("status", text(state, "Status", "status"));
        stateSummary.put("running", value(state, "Running", "running"));
        stateSummary.put("exitCode", value(state, "ExitCode", "exitCode", "ExitCodeLong", "exitCodeLong"));
        stateSummary.put("error", emptyToNull(text(state, "Error", "error")));
        stateSummary.put("health", text(health, "Status", "status"));
        stateSummary.put("startedAt", text(state, "StartedAt", "startedAt"));
        stateSummary.put("finishedAt", text(state, "FinishedAt", "finishedAt"));
        return new RuntimeInspectResponse(
            target,
            source,
            stateSummary,
            text(raw, "Created", "created"),
            integer(value(raw, "RestartCount", "restartCount")),
            text(restartPolicy, "Name", "name"),
            sanitizeContainerMounts(listOfMaps(value(raw, "Mounts", "mounts"))),
            networks.keySet().stream().map(String::valueOf).sorted().toList());
    }

    private RuntimeInspectResponse serviceInspect(RuntimeTarget target, Map<String, Object> source, Map<String, Object> raw) {
        Map<String, Object> spec = map(value(raw, "Spec", "spec"));
        Map<String, Object> taskTemplate = map(value(spec, "TaskTemplate", "taskTemplate"));
        Map<String, Object> containerSpec = map(value(taskTemplate, "ContainerSpec", "containerSpec"));
        Map<String, Object> restartPolicy = map(value(taskTemplate, "RestartPolicy", "restartPolicy"));
        Map<String, Object> stateSummary = new LinkedHashMap<>();
        stateSummary.put("status", RuntimeCleanupPorts.RUNTIME_TYPE_SERVICE);
        stateSummary.put("running", true);
        stateSummary.put("exitCode", null);
        stateSummary.put("error", null);
        stateSummary.put("health", null);
        stateSummary.put("startedAt", null);
        stateSummary.put("finishedAt", null);
        return new RuntimeInspectResponse(
            target,
            source,
            stateSummary,
            text(raw, "CreatedAt", "createdAt"),
            null,
            text(restartPolicy, "Condition", "condition"),
            sanitizeServiceMounts(listOfMaps(value(containerSpec, "Mounts", "mounts"))),
            serviceNetworks(spec, taskTemplate));
    }

    private static List<Map<String, Object>> sanitizeContainerMounts(List<Map<String, Object>> mounts) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> mount : mounts) {
            String type = text(mount, "Type", "type");
            String name = text(mount, "Name", "name");
            String source = text(mount, "Source", "source");
            Map<String, Object> safe = new LinkedHashMap<>();
            safe.put("type", type);
            safe.put("name", name);
            safe.put("destination", firstText(text(mount, "Destination", "destination"), text(mount, "Target", "target")));
            safe.put("mode", text(mount, "Mode", "mode"));
            safe.put("rw", value(mount, "RW", "rw", "ReadOnly", "readOnly") instanceof Boolean readOnly ? !readOnly : value(mount, "RW", "rw"));
            safe.put("propagation", text(mount, "Propagation", "propagation"));
            safe.put("source", MOUNT_TYPE_VOLUME.equalsIgnoreCase(type) || name != null ? source : source == null ? null : REDACTED);
            result.add(safe);
        }
        return List.copyOf(result);
    }

    private static List<Map<String, Object>> sanitizeServiceMounts(List<Map<String, Object>> mounts) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> mount : mounts) {
            String type = text(mount, "Type", "type");
            String source = text(mount, "Source", "source");
            Boolean readOnly = bool(value(mount, "ReadOnly", "readOnly"));
            Map<String, Object> safe = new LinkedHashMap<>();
            safe.put("type", type);
            safe.put("name", MOUNT_TYPE_VOLUME.equalsIgnoreCase(type) ? source : null);
            safe.put("destination", text(mount, "Target", "target"));
            safe.put("mode", readOnly == null ? null : readOnly ? "ro" : "rw");
            safe.put("rw", readOnly == null ? null : !readOnly);
            safe.put("source", MOUNT_TYPE_VOLUME.equalsIgnoreCase(type) ? source : source == null ? null : REDACTED);
            result.add(safe);
        }
        return List.copyOf(result);
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

    private BlockedResource blocked(ComputeRuntimeResource resource, String reason) {
        return new BlockedResource(
            resource.runtimeId(),
            resource.runtimeType(),
            resource.name(),
            resource.state(),
            reason,
            pockethiveLabelsOnly(labels(resource)));
    }

    private static Map<String, String> labels(ComputeRuntimeResource resource) {
        return resource.labels() == null ? Map.of() : resource.labels();
    }

    private static List<String> missingLabels(Map<String, String> labels) {
        return REQUIRED_LABELS.stream()
            .filter(label -> optionalText(labels.get(label)) == null)
            .toList();
    }

    private static boolean isRunning(ComputeRuntimeResource resource) {
        if (RuntimeCleanupPorts.RUNTIME_TYPE_SERVICE.equals(resource.runtimeType())) {
            return true;
        }
        String state = String.valueOf(resource.state()).toLowerCase(Locale.ROOT);
        return state.equals("running") || state.equals("restarting") || state.equals("created") || state.equals("paused");
    }

    private static int normalizeTailLines(Integer value) {
        if (value == null) {
            return DEFAULT_TAIL_LINES;
        }
        if (value < 1 || value > MAX_TAIL_LINES) {
            throw error(HttpStatus.BAD_REQUEST, "tailLines must be between 1 and " + MAX_TAIL_LINES);
        }
        return value;
    }

    private static Integer sinceEpochSeconds(String value) {
        String since = optionalText(value);
        if (since == null) {
            return null;
        }
        try {
            return Math.toIntExact(Long.parseLong(since));
        } catch (NumberFormatException ignored) {
            // Try ISO-8601 below.
        }
        try {
            return Math.toIntExact(Instant.parse(since).getEpochSecond());
        } catch (DateTimeParseException | ArithmeticException ex) {
            throw error(HttpStatus.BAD_REQUEST, "since must be epoch seconds or an ISO-8601 instant");
        }
    }

    private static String redact(String value) {
        String text = String.valueOf(value == null ? "" : value);
        text = AUTHORIZATION_PATTERN.matcher(text).replaceAll("$1" + REDACTED);
        return SECRET_PATTERN.matcher(text).replaceAll("$1" + REDACTED);
    }

    private static int countLines(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String line : value.split("\\R")) {
            if (!line.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private static ImageReference parseImageReference(String value) {
        String image = optionalText(value);
        if (image == null) {
            return new ImageReference(null, null, null);
        }
        int digestIndex = image.indexOf('@');
        String imageWithoutDigest = digestIndex >= 0 ? image.substring(0, digestIndex) : image;
        String imageDigest = digestIndex >= 0 ? optionalText(image.substring(digestIndex + 1)) : null;
        int lastSlash = imageWithoutDigest.lastIndexOf('/');
        int lastColon = imageWithoutDigest.lastIndexOf(':');
        String imageTag = lastColon > lastSlash ? optionalText(imageWithoutDigest.substring(lastColon + 1)) : null;
        return new ImageReference(image, imageTag, imageDigest);
    }

    private static Map<String, String> pockethiveLabelsOnly(Map<String, String> labels) {
        Map<String, String> safe = new LinkedHashMap<>();
        labels.entrySet().stream()
            .filter(entry -> entry.getKey().startsWith(PocketHiveDockerLabels.LABEL_PREFIX))
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> safe.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(safe);
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

    private static String requireText(String value, String name) {
        String text = optionalText(value);
        if (text == null) {
            throw error(HttpStatus.BAD_REQUEST, name + " must not be blank");
        }
        return text;
    }

    private static void requireRequest(Object request) {
        if (request == null) {
            throw error(HttpStatus.BAD_REQUEST, "request body is required");
        }
    }

    private String adapterType() {
        ComputeAdapterType adapterType = computeAdapter.type();
        if (adapterType == null || adapterType == ComputeAdapterType.AUTO) {
            throw error(HttpStatus.INTERNAL_SERVER_ERROR, "ComputeAdapter must expose a concrete adapter type");
        }
        return adapterType.name();
    }

    private static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static int compareEntry(RuntimeEntry left, RuntimeEntry right) {
        return left.runtimeId().compareTo(right.runtimeId());
    }

    private static int compareBlocked(BlockedResource left, BlockedResource right) {
        return left.runtimeId().compareTo(right.runtimeId());
    }

    private static int compareTarget(RuntimeTarget left, RuntimeTarget right) {
        return Comparator.comparing(RuntimeTarget::runtimeId).compare(left, right);
    }

    private static RuntimeDebugException error(HttpStatus status, String message) {
        return new RuntimeDebugException(status, message);
    }

    private record ImageReference(String image, String imageTag, String imageDigest) {
    }
}
