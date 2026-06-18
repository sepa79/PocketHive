package io.pockethive.orchestrator.runtime;

import io.pockethive.controlplane.spring.ControlPlaneProperties;
import io.pockethive.controlplane.topology.ControlPlaneTopologySettings;
import io.pockethive.controlplane.topology.ControlQueueDescriptor;
import io.pockethive.controlplane.topology.WorkerControlPlaneTopologyDescriptor;
import io.pockethive.docker.compute.PocketHiveDockerLabels;
import io.pockethive.manager.runtime.ComputeAdapterType;
import io.pockethive.orchestrator.app.ContainerLifecycleManager;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmLifecycleStatus;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.orchestrator.runtime.RuntimeCleanupContracts.Blocked;
import io.pockethive.orchestrator.runtime.RuntimeCleanupContracts.Candidate;
import io.pockethive.orchestrator.runtime.RuntimeCleanupContracts.CandidateResult;
import io.pockethive.orchestrator.runtime.RuntimeCleanupContracts.Evidence;
import io.pockethive.orchestrator.runtime.RuntimeCleanupContracts.ExecuteRequest;
import io.pockethive.orchestrator.runtime.RuntimeCleanupContracts.ExecuteResponse;
import io.pockethive.orchestrator.runtime.RuntimeCleanupContracts.Plan;
import io.pockethive.orchestrator.runtime.RuntimeCleanupContracts.PlanRequest;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.ComputeRuntimeInventoryPort;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.ComputeRuntimeRemovalPort;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.ComputeRuntimeResource;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.RabbitExchangeResource;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.RabbitQueueResource;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.RabbitTopologyPort;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.RuntimeOwnershipManifestStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

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
    private final RabbitTopologyPort rabbitTopology;
    private final RuntimeCleanupEvidenceStore evidenceStore;
    private final ContainerLifecycleManager lifecycleManager;
    private final ControlPlaneProperties controlPlaneProperties;

    public RuntimeReconciliationService(
        SwarmStore swarmStore,
        RuntimeOwnershipManifestStore manifestStore,
        ComputeRuntimeInventoryPort computeInventory,
        ComputeRuntimeRemovalPort computeRemoval,
        RabbitTopologyPort rabbitTopology,
        RuntimeCleanupEvidenceStore evidenceStore,
        ContainerLifecycleManager lifecycleManager,
        ControlPlaneProperties controlPlaneProperties) {
        this.swarmStore = Objects.requireNonNull(swarmStore, "swarmStore");
        this.manifestStore = Objects.requireNonNull(manifestStore, "manifestStore");
        this.computeInventory = Objects.requireNonNull(computeInventory, "computeInventory");
        this.computeRemoval = Objects.requireNonNull(computeRemoval, "computeRemoval");
        this.rabbitTopology = Objects.requireNonNull(rabbitTopology, "rabbitTopology");
        this.evidenceStore = Objects.requireNonNull(evidenceStore, "evidenceStore");
        this.lifecycleManager = Objects.requireNonNull(lifecycleManager, "lifecycleManager");
        this.controlPlaneProperties = Objects.requireNonNull(controlPlaneProperties, "controlPlaneProperties");
    }

    public Plan plan(PlanRequest request) {
        CleanupScope scope = CleanupScope.from(request);
        Optional<Swarm> swarmForId = swarmStore.find(scope.swarmId());
        Optional<Swarm> lifecycleSwarm = swarmForId
            .filter(swarm -> scope.runId().isEmpty() || scope.runId().get().equals(swarm.getRunId()));
        Optional<RuntimeOwnershipManifest> manifest = manifest(scope);
        List<ComputeRuntimeResource> computeResources = computeInventory.list(scope.computeAdapter());
        List<Candidate> candidates = new ArrayList<>();
        List<Blocked> blocked = new ArrayList<>();

        lifecycleSwarm.ifPresent(swarm -> appendLifecycleCandidate(scope, swarm, candidates, blocked));
        appendComputeCandidates(scope, lifecycleSwarm, computeResources, candidates, blocked);
        appendRabbitCandidates(scope, swarmForId, manifest, computeResources, candidates, blocked);

        candidates.sort(Comparator.comparing(Candidate::candidateId));
        blocked.sort(Comparator.comparing(Blocked::candidateId));

        Plan base = new Plan(
            scope.computeAdapter(),
            scope.swarmId(),
            scope.runId().orElse(null),
            scope.includeRunning(),
            scope.includeRabbit(),
            scope.overrideRegisteredSwarmState(),
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
            base.overrideRegisteredSwarmState(),
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
            request.computeAdapter(),
            request.swarmId(),
            request.runId(),
            request.includeRunning(),
            request.includeRabbit(),
            request.overrideRegisteredSwarmState()));
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
            CandidateResult result = executeCandidate(candidate);
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
            if (activeSwarm.isPresent()
                && PocketHiveDockerLabels.RESOURCE_KIND_MANAGER.equals(labels.get(PocketHiveDockerLabels.RESOURCE_KIND))) {
                blocked.add(blocked(resource, "registered swarm controller must be removed through lifecycle cleanup"));
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

    private void appendRabbitCandidates(CleanupScope scope,
                                        Optional<Swarm> activeSwarm,
                                        Optional<RuntimeOwnershipManifest> manifest,
                                        List<ComputeRuntimeResource> computeResources,
                                        List<Candidate> candidates,
                                        List<Blocked> blocked) {
        if (!scope.includeRabbit()) {
            return;
        }
        if (manifest.isEmpty()) {
            blocked.add(new Blocked(
                "rabbit:manifest:" + scope.swarmId(),
                RuntimeCleanupAction.DELETE_RABBIT_QUEUE,
                scope.swarmId(),
                "manifest",
                "missing ownership manifest",
                Map.of()));
            return;
        }
        RuntimeOwnershipManifest.RabbitResources rabbit = manifest.get().rabbit();
        LinkedHashSet<String> queues = new LinkedHashSet<>(concat(rabbit.controlQueues(), rabbit.workQueues()));
        queues.addAll(derivedWorkerControlQueues(scope, computeResources));
        for (String queue : queues) {
            Optional<RabbitQueueResource> live = rabbitTopology.queue(queue);
            if (live.isEmpty()) {
                continue;
            }
            boolean sharedWorkQueue = rabbit.workQueues().contains(queue);
            if (activeSwarm.isPresent() && sharedWorkQueue) {
                blocked.add(new Blocked(
                    rabbitCandidateId(RuntimeCleanupAction.DELETE_RABBIT_QUEUE, queue),
                    RuntimeCleanupAction.DELETE_RABBIT_QUEUE,
                    queue,
                    "queue",
                    "active swarm shared RabbitMQ resource is protected",
                    Map.of()));
                continue;
            }
            RabbitQueueResource q = live.get();
            boolean highRisk = q.depth() > 0 || q.consumers() > 0;
            candidates.add(new Candidate(
                rabbitCandidateId(RuntimeCleanupAction.DELETE_RABBIT_QUEUE, queue),
                RuntimeCleanupAction.DELETE_RABBIT_QUEUE,
                queue,
                "queue",
                null,
                null,
                null,
                "present",
                null,
                q.depth(),
                q.consumers(),
                q.consumers() > 0,
                highRisk,
                highRisk ? "RabbitMQ queue has messages or consumers" : "orphaned RabbitMQ queue from ownership manifest",
                Map.of()));
        }
        for (String exchange : rabbit.exchanges()) {
            Optional<RabbitExchangeResource> live = rabbitTopology.exchange(exchange);
            if (live.isEmpty()) {
                continue;
            }
            if (activeSwarm.isPresent()) {
                blocked.add(new Blocked(
                    rabbitCandidateId(RuntimeCleanupAction.DELETE_RABBIT_EXCHANGE, exchange),
                    RuntimeCleanupAction.DELETE_RABBIT_EXCHANGE,
                    exchange,
                    "exchange",
                    "active swarm shared RabbitMQ resource is protected",
                    Map.of()));
                continue;
            }
            candidates.add(new Candidate(
                rabbitCandidateId(RuntimeCleanupAction.DELETE_RABBIT_EXCHANGE, exchange),
                RuntimeCleanupAction.DELETE_RABBIT_EXCHANGE,
                exchange,
                "exchange",
                null,
                null,
                null,
                "manifested",
                null,
                null,
                null,
                false,
                false,
                "orphaned RabbitMQ exchange from ownership manifest",
                Map.of()));
        }
    }

    private List<String> derivedWorkerControlQueues(CleanupScope scope, List<ComputeRuntimeResource> resources) {
        ControlPlaneTopologySettings settings = new ControlPlaneTopologySettings(
            scope.swarmId(),
            controlPlaneProperties.getControlQueuePrefix(),
            Map.of());
        LinkedHashSet<String> queues = new LinkedHashSet<>();
        for (ComputeRuntimeResource resource : resources) {
            Map<String, String> labels = resource.labels();
            if (!PocketHiveDockerLabels.MANAGED_VALUE.equals(labels.get(PocketHiveDockerLabels.MANAGED))) {
                continue;
            }
            if (!scope.swarmId().equals(labels.get(PocketHiveDockerLabels.SWARM_ID))) {
                continue;
            }
            if (scope.runId().isPresent() && !scope.runId().get().equals(labels.get(PocketHiveDockerLabels.RUN_ID))) {
                continue;
            }
            if (!PocketHiveDockerLabels.RESOURCE_KIND_WORKER.equals(labels.get(PocketHiveDockerLabels.RESOURCE_KIND))) {
                continue;
            }
            if (isRunningState(resource.state()) && !scope.includeRunning()) {
                continue;
            }
            String role = labels.get(PocketHiveDockerLabels.ROLE);
            String instance = labels.get(PocketHiveDockerLabels.INSTANCE);
            if (!hasText(role) || !hasText(instance)) {
                continue;
            }
            new WorkerControlPlaneTopologyDescriptor(role, settings)
                .controlQueue(instance)
                .map(ControlQueueDescriptor::name)
                .ifPresent(queues::add);
        }
        return List.copyOf(queues);
    }

    private void appendLifecycleCandidate(CleanupScope scope,
                                          Swarm swarm,
                                          List<Candidate> candidates,
                                          List<Blocked> blocked) {
        if (!canCleanupRegisteredSwarm(swarm.getStatus())) {
            if (scope.overrideRegisteredSwarmState() && canEmergencyOverrideRegisteredSwarm(swarm.getStatus())) {
                candidates.add(lifecycleCandidate(scope, swarm, true));
                return;
            }
            blocked.add(new Blocked(
                "lifecycle:swarm:" + scope.swarmId(),
                RuntimeCleanupAction.LIFECYCLE_REMOVE_SWARM,
                scope.swarmId(),
                "swarm",
                blockedRegisteredSwarmReason(swarm.getStatus()),
                Map.of()));
            return;
        }
        candidates.add(lifecycleCandidate(scope, swarm, false));
    }

    private static boolean canCleanupRegisteredSwarm(SwarmLifecycleStatus status) {
        return switch (status) {
            case NEW, CREATING, READY, STOPPED, FAILED -> true;
            case STARTING, RUNNING, STOPPING, REMOVING, REMOVED -> false;
        };
    }

    private static boolean canEmergencyOverrideRegisteredSwarm(SwarmLifecycleStatus status) {
        return switch (status) {
            case STARTING, RUNNING, STOPPING, REMOVING -> true;
            case NEW, CREATING, READY, STOPPED, FAILED, REMOVED -> false;
        };
    }

    private static String blockedRegisteredSwarmReason(SwarmLifecycleStatus status) {
        return switch (status) {
            case STARTING, RUNNING, STOPPING -> "registered swarm status " + status
                + " must be explicitly stopped before runtime cleanup";
            case REMOVING -> "registered swarm status REMOVING requires lifecycle recovery; "
                + "runtime cleanup cannot bypass lifecycle removal";
            case REMOVED -> "registered swarm status REMOVED should not remain registered; "
                + "reconcile swarm registry before runtime cleanup";
            case NEW, CREATING, READY, STOPPED, FAILED -> "registered swarm status " + status + " is cleanup eligible";
        };
    }

    private Candidate lifecycleCandidate(CleanupScope scope, Swarm swarm, boolean emergencyOverride) {
        return new Candidate(
            "lifecycle:swarm:" + scope.swarmId(),
            RuntimeCleanupAction.LIFECYCLE_REMOVE_SWARM,
            scope.swarmId(),
            "swarm",
            PocketHiveDockerLabels.RESOURCE_KIND_MANAGER,
            "swarm-controller",
            swarm.getInstanceId(),
            swarm.getStatus().name(),
            swarm.controllerImage(),
            null,
            null,
            emergencyOverride,
            emergencyOverride,
            emergencyOverride
                ? "emergency override: registered swarm state "
                    + swarm.getStatus() + " will be removed through Orchestrator lifecycle"
                : "registered swarm must be removed through Orchestrator lifecycle",
            Map.of());
    }

    private Candidate candidate(ComputeRuntimeResource resource, boolean running) {
        Map<String, String> labels = resource.labels();
        RuntimeCleanupAction action = switch (resource.runtimeType()) {
            case "container" -> RuntimeCleanupAction.DELETE_DOCKER_CONTAINER;
            case "service" -> RuntimeCleanupAction.DELETE_DOCKER_SERVICE;
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
            labels);
    }

    private Blocked blocked(ComputeRuntimeResource resource, String reason) {
        RuntimeCleanupAction action = "service".equals(resource.runtimeType())
            ? RuntimeCleanupAction.DELETE_DOCKER_SERVICE
            : RuntimeCleanupAction.DELETE_DOCKER_CONTAINER;
        return new Blocked(
            dockerCandidateId(action, resource.runtimeId()),
            action,
            resource.runtimeId(),
            resource.runtimeType(),
            reason,
            resource.labels());
    }

    private CandidateResult executeCandidate(Candidate candidate) {
        try {
            switch (candidate.action()) {
                case LIFECYCLE_REMOVE_SWARM -> lifecycleManager.removeSwarm(candidate.resourceId());
                case DELETE_DOCKER_CONTAINER -> computeRemoval.removeContainer(candidate.resourceId());
                case DELETE_DOCKER_SERVICE -> computeRemoval.removeService(candidate.resourceId());
                case DELETE_RABBIT_QUEUE -> rabbitTopology.deleteQueue(candidate.resourceId());
                case DELETE_RABBIT_EXCHANGE -> rabbitTopology.deleteExchange(candidate.resourceId());
            }
            return new CandidateResult(
                candidate.candidateId(),
                candidate.action(),
                candidate.resourceId(),
                RuntimeCleanupStatus.REMOVED,
                null);
        } catch (RuntimeException ex) {
            return new CandidateResult(
                candidate.candidateId(),
                candidate.action(),
                candidate.resourceId(),
                RuntimeCleanupStatus.FAILED,
                ex.getMessage());
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

    private static String planHash(Plan plan) {
        String canonical = plan.computeAdapter() + "\n"
            + plan.swarmId() + "\n"
            + Objects.toString(plan.runId(), "") + "\n"
            + plan.includeRunning() + "\n"
            + plan.includeRabbit() + "\n"
            + plan.overrideRegisteredSwarmState() + "\n"
            + plan.candidates().stream()
                .map(c -> c.candidateId() + "|" + c.action() + "|" + c.resourceId() + "|" + c.highRisk())
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
        String type = action == RuntimeCleanupAction.DELETE_DOCKER_SERVICE ? "service" : "container";
        return "docker:" + type + ":" + runtimeId;
    }

    private static String rabbitCandidateId(RuntimeCleanupAction action, String name) {
        String type = action == RuntimeCleanupAction.DELETE_RABBIT_EXCHANGE ? "exchange" : "queue";
        return "rabbit:" + type + ":" + name;
    }

    private static boolean isRunningState(String state) {
        if (state == null) {
            return false;
        }
        String normalized = state.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("running")
            || normalized.equals("created")
            || normalized.equals("paused")
            || normalized.equals("restarting")
            || normalized.equals("service");
    }

    private static String requireText(String value, String label) {
        if (!hasText(value)) {
            throw cleanupError(HttpStatus.BAD_REQUEST, label + " must not be blank");
        }
        return value.trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String firstText(String first, String second) {
        return hasText(first) ? first.trim() : hasText(second) ? second.trim() : null;
    }

    private static String defaultActor(String actor) {
        return hasText(actor) ? actor.trim() : "orchestrator-api";
    }

    private static <T> List<T> concat(List<T> first, List<T> second) {
        List<T> result = new ArrayList<>();
        if (first != null) {
            result.addAll(first);
        }
        if (second != null) {
            result.addAll(second);
        }
        return result;
    }

    private static RuntimeCleanupException cleanupError(HttpStatus status, String message) {
        return new RuntimeCleanupException(status, message);
    }

    private record CleanupScope(
        String computeAdapter,
        String swarmId,
        Optional<String> runId,
        boolean includeRunning,
        boolean includeRabbit,
        boolean overrideRegisteredSwarmState) {
        static CleanupScope from(PlanRequest request) {
            if (request == null) {
                throw cleanupError(HttpStatus.BAD_REQUEST, "request body is required");
            }
            String computeAdapter = requireText(request.computeAdapter(), "computeAdapter");
            ComputeAdapterType adapterType;
            try {
                adapterType = ComputeAdapterType.valueOf(computeAdapter);
            } catch (IllegalArgumentException ex) {
                throw cleanupError(HttpStatus.BAD_REQUEST, "unsupported computeAdapter: " + computeAdapter);
            }
            if (adapterType == ComputeAdapterType.AUTO) {
                throw cleanupError(HttpStatus.BAD_REQUEST, "computeAdapter must be concrete");
            }
            return new CleanupScope(
                adapterType.name(),
                requireText(request.swarmId(), "swarmId"),
                hasText(request.runId()) ? Optional.of(request.runId().trim()) : Optional.empty(),
                Boolean.TRUE.equals(request.includeRunning()),
                !Boolean.FALSE.equals(request.includeRabbit()),
                Boolean.TRUE.equals(request.overrideRegisteredSwarmState()));
        }
    }
}
