package io.pockethive.orchestrator.runtime;

import io.pockethive.controlplane.spring.ControlPlaneProperties;
import io.pockethive.docker.compute.PocketHiveDockerLabels;
import io.pockethive.orchestrator.app.ContainerLifecycleManager;
import io.pockethive.orchestrator.app.SwarmLifecycleCommandService;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.orchestrator.runtime.RuntimeCleanupContracts.Evidence;
import io.pockethive.orchestrator.runtime.RuntimeCleanupContracts.ExecuteRequest;
import io.pockethive.orchestrator.runtime.RuntimeCleanupContracts.ExecuteResponse;
import io.pockethive.orchestrator.runtime.RuntimeCleanupContracts.Plan;
import io.pockethive.orchestrator.runtime.RuntimeCleanupContracts.PlanRequest;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.ComputeRuntimeInventoryPort;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.ComputeRuntimeRemovalPort;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.ComputeRuntimeResource;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.RuntimeOwnershipManifestStore;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.RabbitTopologyRequest;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.RabbitTopologySnapshot;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.SourceSummary;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Responsibility: coordinate governed runtime cleanup planning and execution through resource owners.
 * Must not: infer Rabbit plane from names or implement Rabbit resource observation rules.
 * Contract: docs/ORCHESTRATOR-REST.md#2910-execute-cleanup.
 */
@Service
public class RuntimeReconciliationService {
    private static final Set<String> REQUIRED_LABELS = Set.of(
        PocketHiveDockerLabels.MANAGED,
        PocketHiveDockerLabels.SWARM_ID,
        PocketHiveDockerLabels.RUN_ID,
        PocketHiveDockerLabels.RESOURCE_KIND,
        PocketHiveDockerLabels.ROLE,
        PocketHiveDockerLabels.INSTANCE);
    private final SwarmStore swarmStore;
    private final RuntimeOwnershipManifestStore manifestStore;
    private final ComputeRuntimeInventoryPort computeInventory;
    private final ComputeRuntimeRemovalPort computeRemoval;
    private final RuntimeRabbitResourcePlanner rabbitPlanner;
    private final RuntimeCleanupEvidenceStore evidenceStore;
    private final ContainerLifecycleManager lifecycleManager;
    private final SwarmLifecycleCommandService lifecycleCommands;
    private final ControlPlaneProperties controlPlaneProperties;

    public RuntimeReconciliationService(
        SwarmStore swarmStore,
        RuntimeOwnershipManifestStore manifestStore,
        ComputeRuntimeInventoryPort computeInventory,
        ComputeRuntimeRemovalPort computeRemoval,
        RabbitTopologyPort rabbitTopology,
        RuntimeCleanupEvidenceStore evidenceStore,
        ContainerLifecycleManager lifecycleManager,
        SwarmLifecycleCommandService lifecycleCommands,
        ControlPlaneProperties controlPlaneProperties) {
        this.swarmStore = Objects.requireNonNull(swarmStore, "swarmStore");
        this.manifestStore = Objects.requireNonNull(manifestStore, "manifestStore");
        this.computeInventory = Objects.requireNonNull(computeInventory, "computeInventory");
        this.computeRemoval = Objects.requireNonNull(computeRemoval, "computeRemoval");
        this.rabbitPlanner = new RuntimeRabbitResourcePlanner(rabbitTopology, controlPlaneProperties);
        this.evidenceStore = Objects.requireNonNull(evidenceStore, "evidenceStore");
        this.lifecycleManager = Objects.requireNonNull(lifecycleManager, "lifecycleManager");
        this.lifecycleCommands = Objects.requireNonNull(lifecycleCommands, "lifecycleCommands");
        this.controlPlaneProperties = Objects.requireNonNull(controlPlaneProperties, "controlPlaneProperties");
    }

    public Plan plan(PlanRequest request) {
        CleanupScope scope = cleanupScope(request, currentComputeAdapterType());
        Optional<Swarm> swarmForId = swarmStore.find(scope.swarmId());
        Optional<Swarm> lifecycleSwarm = swarmForId
            .filter(swarm -> scope.runId().isEmpty() || scope.runId().get().equals(swarm.getRunId()));
        Optional<RuntimeOwnershipManifest> manifest = manifest(scope);
        List<ComputeRuntimeResource> computeResources = computeInventory.list();
        List<Candidate> candidates = new ArrayList<>();
        List<Blocked> blocked = new ArrayList<>();

        lifecycleSwarm.ifPresent(swarm -> appendLifecycleCandidate(scope, swarm, candidates, blocked));
        appendComputeCandidates(scope, lifecycleSwarm, computeResources, candidates, blocked);
        rabbitPlanner.appendCandidates(scope, swarmForId, manifest, computeResources, candidates, blocked);

        candidates.sort(Comparator.comparing(Candidate::candidateId));
        blocked.sort(Comparator.comparing(Blocked::candidateId));

        Plan base = new Plan(
            scope.computeAdapter(),
            scope.swarmId(),
            scope.runId().orElse(null),
            scope.includeRunning(),
            scope.includeRabbit(),
            "pending",
            executionRisk(scope, candidates),
            List.copyOf(candidates),
            List.copyOf(blocked));
        return new Plan(
            base.computeAdapter(),
            base.swarmId(),
            base.runId(),
            base.includeRunning(),
            base.includeRabbit(),
            planHash(base),
            base.executionRisk(),
            base.candidates(),
            base.blocked());
    }

    public ExecuteResponse execute(ExecuteRequest request) {
        String idempotencyKey = requireText(request.idempotencyKey(), "idempotencyKey");
        String candidateSetHash = requireText(request.candidateSetHash(), "candidateSetHash");
        List<String> candidateIds = requireIds(request.candidateIds());
        String actor = requireText(defaultActor(request.actor()), "actor");
        requireText(request.reason(), "reason");
        Optional<Evidence> previous = evidenceStore.findEvidence(idempotencyKey, actor);
        if (previous.isPresent()) {
            if (!RuntimeCleanupEvidenceStore.sameCleanupInput(previous.get(), candidateSetHash, candidateIds)) {
                throw cleanupError(HttpStatus.CONFLICT, "idempotencyKey was already used for a different cleanup execution");
            }
            return new ExecuteResponse(true, previous.get());
        }

        Plan plan = plan(new PlanRequest(
            request.swarmId(),
            request.runId(),
            request.includeRunning(),
            request.includeRabbit()));
        if (!plan.candidateSetHash().equals(candidateSetHash)) {
            throw cleanupError(HttpStatus.CONFLICT, "candidateSetHash does not match the current cleanup plan");
        }

        Map<String, Candidate> byId = candidatesById(plan);
        Map<String, Blocked> blockedById = blockedById(plan);
        for (String id : candidateIds) {
            if (!byId.containsKey(id)) {
                Blocked blocked = blockedById.get(id);
                if (blocked != null) {
                    throw cleanupError(HttpStatus.CONFLICT, "cleanup candidate is blocked: " + blocked.reason());
                }
                throw cleanupError(HttpStatus.CONFLICT, "candidate is no longer in the current cleanup plan: " + id);
            }
        }

        Instant startedAt = Instant.now();
        List<CandidateResult> results = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (String id : candidateIds) {
            Candidate candidate = byId.get(id);
            CandidateResult result = executeCandidate(candidate, idempotencyKey);
            results.add(result);
            if (result.status() == RuntimeCleanupStatus.FAILED && result.error() != null) {
                errors.add(result.error());
            }
        }
        Evidence evidence = new Evidence(
            actor,
            idempotencyKey,
            plan.computeAdapter(),
            plan.swarmId(),
            plan.runId(),
            plan.candidateSetHash(),
            List.copyOf(candidateIds),
            List.copyOf(results),
            startedAt,
            Instant.now(),
            List.copyOf(errors));
        evidenceStore.saveEvidence(evidence);
        return new ExecuteResponse(false, evidence);
    }

    public RabbitTopologySnapshot rabbitTopology(RabbitTopologyRequest request) {
        CleanupScope scope = cleanupScope(new PlanRequest(
            request == null ? null : request.swarmId(),
            request == null ? null : request.runId(),
            true,
            true),
            currentComputeAdapterType());
        Optional<RuntimeOwnershipManifest> manifest = manifest(scope);
        if (manifest.isEmpty()) {
            return new RabbitTopologySnapshot(
                scope.computeAdapter(),
                scope.swarmId(),
                scope.runId().orElse(null),
                SourceSummary.missing("runtime ownership manifest was not found"),
                SourceSummary.present(),
                true,
                List.of(),
                List.of(),
                List.of());
        }

        return rabbitPlanner.snapshot(scope, manifest.get(), computeInventory.list());
    }

    public RuntimeOwnershipManifest ownershipManifest(RabbitTopologyRequest request) {
        CleanupScope scope = cleanupScope(new PlanRequest(
            request == null ? null : request.swarmId(),
            request == null ? null : request.runId(),
            false,
            false),
            currentComputeAdapterType());
        return manifest(scope).orElseThrow(() -> cleanupError(
            HttpStatus.NOT_FOUND,
            "runtime ownership manifest was not found"));
    }

    private void appendComputeCandidates(CleanupScope scope,
                                         Optional<Swarm> activeSwarm,
                                         List<ComputeRuntimeResource> resources,
                                         List<Candidate> candidates,
                                         List<Blocked> blocked) {
        for (ComputeRuntimeResource resource : resources) {
            Map<String, String> labels = resource.labels();
            if (!PocketHiveDockerLabels.MANAGED_VALUE.equals(labels.get(PocketHiveDockerLabels.MANAGED))) {
                if (scope.swarmId().equals(labels.get(PocketHiveDockerLabels.SWARM_ID))) {
                    blocked.add(blocked(resource, "missing " + PocketHiveDockerLabels.MANAGED + "="
                        + PocketHiveDockerLabels.MANAGED_VALUE));
                }
                continue;
            }
            if (!scope.swarmId().equals(labels.get(PocketHiveDockerLabels.SWARM_ID))) {
                continue;
            }
            List<String> missing = REQUIRED_LABELS.stream()
                .filter(label -> !hasText(labels.get(label)))
                .sorted()
                .toList();
            if (!missing.isEmpty()) {
                blocked.add(blocked(resource, "missing required labels: " + String.join(", ", missing)));
                continue;
            }
            if (scope.runId().isPresent() && !scope.runId().get().equals(labels.get(PocketHiveDockerLabels.RUN_ID))) {
                continue;
            }
            if (activeSwarm.isPresent()) {
                blocked.add(blocked(resource, "registered swarm resources must be removed through lifecycle cleanup"));
                continue;
            }
            boolean running = isRunningState(resource.state());
            if (running && !scope.includeRunning()) {
                blocked.add(blocked(resource, "running resource requires includeRunning=true"));
                continue;
            }
            candidates.add(candidate(resource, running));
        }
    }

    private void appendLifecycleCandidate(CleanupScope scope,
                                          Swarm swarm,
                                          List<Candidate> candidates,
                                          List<Blocked> blocked) {
        if (swarm.getRuntimeIntent() == io.pockethive.swarm.model.lifecycle.RuntimeIntent.ABSENT) {
            blocked.add(new Blocked(
                "lifecycle:swarm:" + scope.swarmId(),
                RuntimeCleanupAction.LIFECYCLE_REMOVE_SWARM,
                scope.swarmId(),
                "swarm",
                "registered swarm already has runtime intent ABSENT; inspect its active REMOVE operation",
                Map.of(), io.pockethive.swarm.model.lifecycle.ResourcePlane.NONE));
            return;
        }
        if (workloadActive(swarm) && !scope.includeRunning()) {
            blocked.add(new Blocked(
                "lifecycle:swarm:" + scope.swarmId(),
                RuntimeCleanupAction.LIFECYCLE_REMOVE_SWARM,
                scope.swarmId(),
                "swarm",
                "active registered swarm requires includeRunning=true",
                Map.of(), io.pockethive.swarm.model.lifecycle.ResourcePlane.NONE));
            return;
        }
        candidates.add(lifecycleCandidate(scope, swarm));
    }

    private Candidate lifecycleCandidate(CleanupScope scope, Swarm swarm) {
        boolean workloadActive = workloadActive(swarm);
        return new Candidate(
            "lifecycle:swarm:" + scope.swarmId(),
            RuntimeCleanupAction.LIFECYCLE_REMOVE_SWARM,
            scope.swarmId(),
            "swarm",
            PocketHiveDockerLabels.RESOURCE_KIND_MANAGER,
            "swarm-controller",
            swarm.getInstanceId(),
            swarm.getRuntimeIntent().name() + "/" + swarm.getWorkloadIntent().name()
                + "/" + swarm.getWorkloadState().name(),
            swarm.controllerImage(),
            null,
            null,
            workloadActive,
            workloadActive,
            "registered swarm must be removed through the canonical REMOVE operation",
            Map.of(), io.pockethive.swarm.model.lifecycle.ResourcePlane.NONE);
    }

    private static boolean workloadActive(Swarm swarm) {
        return switch (swarm.getWorkloadState()) {
            case STARTING, RUNNING, STOPPING -> true;
            case UNAVAILABLE, STOPPED, UNKNOWN -> false;
        };
    }

    private Candidate candidate(ComputeRuntimeResource resource, boolean running) {
        Map<String, String> labels = resource.labels();
        RuntimeCleanupAction action = switch (resource.runtimeType()) {
            case RuntimeCleanupPorts.RUNTIME_TYPE_CONTAINER -> RuntimeCleanupAction.DELETE_DOCKER_CONTAINER;
            case RuntimeCleanupPorts.RUNTIME_TYPE_SERVICE -> RuntimeCleanupAction.DELETE_DOCKER_SERVICE;
            default -> throw cleanupError(HttpStatus.BAD_REQUEST, "unsupported compute runtime type: " + resource.runtimeType());
        };
        return new Candidate(
            dockerCandidateId(action, resource.runtimeId()),
            action,
            resource.runtimeId(),
            resource.runtimeType(),
            labels.get(PocketHiveDockerLabels.RESOURCE_KIND),
            labels.get(PocketHiveDockerLabels.ROLE),
            labels.get(PocketHiveDockerLabels.INSTANCE),
            resource.state(),
            firstText(labels.get(PocketHiveDockerLabels.IMAGE), resource.image()),
            null,
            null,
            running,
            running,
            running ? "running PocketHive runtime resource" : "stopped PocketHive runtime resource",
            pockethiveLabelsOnly(labels), io.pockethive.swarm.model.lifecycle.ResourcePlane.NONE);
    }

    private Blocked blocked(ComputeRuntimeResource resource, String reason) {
        RuntimeCleanupAction action = RuntimeCleanupPorts.RUNTIME_TYPE_SERVICE.equals(resource.runtimeType())
            ? RuntimeCleanupAction.DELETE_DOCKER_SERVICE
            : RuntimeCleanupAction.DELETE_DOCKER_CONTAINER;
        return new Blocked(
            dockerCandidateId(action, resource.runtimeId()),
            action,
            resource.runtimeId(),
            resource.runtimeType(),
            reason,
            pockethiveLabelsOnly(resource.labels()), io.pockethive.swarm.model.lifecycle.ResourcePlane.NONE);
    }

    private static Map<String, String> pockethiveLabelsOnly(Map<String, String> labels) {
        if (labels == null || labels.isEmpty()) {
            return Map.of();
        }
        Map<String, String> safe = new LinkedHashMap<>();
        labels.entrySet().stream()
            .filter(entry -> entry.getKey() != null && entry.getKey().startsWith(PocketHiveDockerLabels.LABEL_PREFIX))
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> safe.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(safe);
    }

    private CandidateResult executeCandidate(Candidate candidate, String idempotencyKey) {
        try {
            if (candidate.action() == RuntimeCleanupAction.LIFECYCLE_REMOVE_SWARM) {
                var reservation = lifecycleCommands.dispatch(
                    io.pockethive.swarm.model.lifecycle.OperationType.REMOVE,
                    candidate.resourceId(),
                    idempotencyKey,
                    Duration.ofSeconds(180));
                return new CandidateResult(
                    candidate.candidateId(),
                    candidate.action(),
                    candidate.resourceId(),
                    RuntimeCleanupStatus.DISPATCHED,
                    reservation.operation().correlationId(),
                    "/api/swarms/" + candidate.resourceId() + "/operations/"
                        + reservation.operation().correlationId(),
                    null, candidate.plane());
            }
            switch (candidate.action()) {
                case DELETE_DOCKER_CONTAINER -> computeRemoval.removeContainer(candidate.resourceId());
                case DELETE_DOCKER_SERVICE -> computeRemoval.removeService(candidate.resourceId());
                case DELETE_RABBIT_QUEUE -> rabbitPlanner.deleteQueue(candidate);
                case DELETE_RABBIT_EXCHANGE -> rabbitPlanner.deleteExchange(candidate);
                case LIFECYCLE_REMOVE_SWARM -> throw new IllegalStateException("handled above");
            }
            return new CandidateResult(
                candidate.candidateId(),
                candidate.action(),
                candidate.resourceId(),
                RuntimeCleanupStatus.REMOVED,
                null,
                null,
                null, candidate.plane());
        } catch (RuntimeException ex) {
            return new CandidateResult(
                candidate.candidateId(),
                candidate.action(),
                candidate.resourceId(),
                RuntimeCleanupStatus.FAILED,
                null,
                null,
                ex.getMessage(), candidate.plane());
        }
    }

    private Optional<RuntimeOwnershipManifest> manifest(CleanupScope scope) {
        if (scope.runId().isPresent()) {
            return manifestStore.find(scope.swarmId(), scope.runId().get());
        }
        return manifestStore.findLatest(scope.swarmId());
    }

    private static Map<String, Candidate> candidatesById(Plan plan) {
        return plan.candidates().stream()
            .collect(Collectors.toMap(Candidate::candidateId, c -> c, (a, b) -> a, LinkedHashMap::new));
    }

    private static Map<String, Blocked> blockedById(Plan plan) {
        return plan.blocked().stream()
            .collect(Collectors.toMap(Blocked::candidateId, b -> b, (a, b) -> a, LinkedHashMap::new));
    }

    private static List<String> requireIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw cleanupError(HttpStatus.BAD_REQUEST, "candidateIds must not be empty");
        }
        return ids.stream().map(id -> requireText(id, "candidateId")).distinct().toList();
    }

    private String planHash(Plan plan) {
        String canonical = plan.computeAdapter() + "\n"
            + plan.swarmId() + "\n"
            + Objects.toString(plan.runId(), "") + "\n"
            + plan.includeRunning() + "\n"
            + plan.includeRabbit() + "\n"
            + (plan.candidates().stream().anyMatch(c -> c.action() == RuntimeCleanupAction.LIFECYCLE_REMOVE_SWARM)
                ? rabbitPlanner.connectionIdentity(io.pockethive.swarm.model.lifecycle.ResourcePlane.CONTROL) + "|"
                    + rabbitPlanner.connectionIdentity(io.pockethive.swarm.model.lifecycle.ResourcePlane.WORK) + "\n"
                : "")
            + plan.candidates().stream()
                .map(c -> c.candidateId() + "|" + c.action() + "|" + c.resourceId() + "|" + c.plane() + "|" + c.highRisk()
                    + (c.plane() == io.pockethive.swarm.model.lifecycle.ResourcePlane.NONE ? "" : "|" + rabbitPlanner.connectionIdentity(c.plane())))
                .sorted()
                .collect(Collectors.joining("\n"));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static String executionRisk(CleanupScope scope, List<Candidate> candidates) {
        if (candidates.isEmpty()) {
            return "none";
        }
        if (scope.runId().isEmpty() || candidates.size() > 10 || candidates.stream().anyMatch(Candidate::highRisk)) {
            return "high";
        }
        return "standard";
    }

    private static String dockerCandidateId(RuntimeCleanupAction action, String runtimeId) {
        String type = action == RuntimeCleanupAction.DELETE_DOCKER_SERVICE
            ? RuntimeCleanupPorts.RUNTIME_TYPE_SERVICE
            : RuntimeCleanupPorts.RUNTIME_TYPE_CONTAINER;
        return "docker:" + type + ":" + runtimeId;
    }

    static boolean isRunningState(String state) {
        if (state == null) {
            return false;
        }
        String normalized = state.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("running")
            || normalized.equals("created")
            || normalized.equals("paused")
            || normalized.equals("restarting")
            || normalized.equals(RuntimeCleanupPorts.RUNTIME_TYPE_SERVICE);
    }

    private static String requireText(String value, String label) {
        if (!hasText(value)) {
            throw cleanupError(HttpStatus.BAD_REQUEST, label + " must not be blank");
        }
        return value.trim();
    }

    static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String firstText(String first, String second) {
        return hasText(first) ? first.trim() : hasText(second) ? second.trim() : null;
    }

    private static String defaultActor(String actor) {
        return hasText(actor) ? actor.trim() : "orchestrator-api";
    }

    private static RuntimeCleanupException cleanupError(HttpStatus status, String message) {
        return new RuntimeCleanupException(status, message);
    }

    private static CleanupScope cleanupScope(PlanRequest request, String computeAdapter) {
        if (request == null) {
            throw cleanupError(HttpStatus.BAD_REQUEST, "request body is required");
        }
        return new CleanupScope(
            requireText(computeAdapter, "computeAdapter"),
            requireText(request.swarmId(), "swarmId"),
            hasText(request.runId()) ? Optional.of(request.runId().trim()) : Optional.empty(),
            Boolean.TRUE.equals(request.includeRunning()),
            !Boolean.FALSE.equals(request.includeRabbit()));
    }

    private String currentComputeAdapterType() {
        return lifecycleManager.currentComputeAdapterType().name();
    }
}
