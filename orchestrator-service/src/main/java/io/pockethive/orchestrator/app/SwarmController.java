package io.pockethive.orchestrator.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlScope;
import io.pockethive.control.ControlSignal;
import io.pockethive.control.ConfirmationScope;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.ControlPlaneOperations;
import io.pockethive.controlplane.ControlPlaneRoles;
import io.pockethive.controlplane.messaging.Alerts;
import io.pockethive.controlplane.messaging.ControlSignals;
import io.pockethive.controlplane.messaging.EventMessage;
import io.pockethive.controlplane.messaging.SignalMessage;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.spring.ControlPlaneProperties;
import io.pockethive.observability.ControlPlaneJson;
import io.pockethive.auth.contract.AuthenticatedUserDto;
import io.pockethive.orchestrator.domain.ScenarioPlan;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.HiveJournal;
import io.pockethive.orchestrator.domain.HiveJournal.HiveJournalEntry;
import io.pockethive.controlplane.filesystem.FilesystemSwarmStartupArtifactStore;
import io.pockethive.orchestrator.domain.SwarmStateStore;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.orchestrator.domain.SwarmOperationCoordinator;
import io.pockethive.orchestrator.domain.SwarmTemplateMetadata;
import io.pockethive.orchestrator.auth.OrchestratorAuthorization;
import io.pockethive.orchestrator.auth.OrchestratorCurrentUserHolder;
import io.pockethive.swarm.model.BeeConfigKeys;
import io.pockethive.swarm.model.BeeRoles;
import io.pockethive.swarm.model.NetworkMode;
import io.pockethive.swarm.model.NetworkBinding;
import io.pockethive.swarm.model.NetworkProfile;
import io.pockethive.swarm.model.ResolvedSutEnvironment;
import io.pockethive.swarm.model.SwarmPlan;
import io.pockethive.swarm.model.SwarmStartupArtifact;
import io.pockethive.swarm.model.SwarmStartupArtifactReference;
import io.pockethive.swarm.model.SwarmTemplate;
import io.pockethive.swarm.model.SutEndpoint;
import io.pockethive.swarm.model.SutEnvironment;
import io.pockethive.swarm.model.lifecycle.ControlResponse;
import io.pockethive.swarm.model.lifecycle.ControlRequest;
import io.pockethive.swarm.model.lifecycle.SwarmCreateRequest;
import io.pockethive.swarm.model.lifecycle.OperationState;
import io.pockethive.swarm.model.lifecycle.OperationType;
import io.pockethive.swarm.model.lifecycle.TerminalResult;
import io.pockethive.swarm.model.lifecycle.TerminalStatus;
import io.pockethive.swarm.model.lifecycle.Target;
import io.pockethive.swarm.model.lifecycle.SwarmStateView;
import io.pockethive.swarm.model.lifecycle.WorkerSummary;
import io.pockethive.util.BeeNameGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller that exposes the swarm lifecycle API consumed by UI operators and automation.
 * <p>
 * Each method below corresponds to an endpoint documented in {@code docs/ORCHESTRATOR-REST.md}. The
 * controller wraps AMQP interactions with idempotency tracking so clients can safely retry requests
 * using the same {@code idempotencyKey}. Inline examples show the JSON bodies that junior developers
 * can post with {@code curl} or their HTTP client of choice.
 */
@RestController
@RequestMapping("/api/swarms")
public class SwarmController {
    private static final Logger log = LoggerFactory.getLogger(SwarmController.class);
    private static final Duration STATUS_FULL_STALE_AFTER = Duration.ofSeconds(30);
    private final ControlPlanePublisher controlPublisher;
    private final ContainerLifecycleManager lifecycle;
    private final SwarmOperationCoordinator operations;
    private final OperationDispatchService operationDispatch;
    private final SwarmLifecycleCommandService lifecycleCommands;
    private final ControlResponseFactory controlResponses;
    private final SwarmStore store;
    private final SwarmStateStore stateStore;
    private final FilesystemSwarmStartupArtifactStore startupArtifacts;
    private final ScenarioClient scenarios;
    private final SwarmNetworkBindingService networkBindings;
    private final HiveJournal hiveJournal;
    private final OrchestratorAuthorization authorization;
    private final ObjectMapper json;
    private final String originInstanceId;
    @Value("${" + io.pockethive.swarm.model.RuntimeFilesystemContract.HOST_ROOT_ENV + ":}")
    private String scenariosRuntimeRootSource;
    private static final Set<String> AUTH_SUT_CONTEXT_IMAGE_NAMES =
        Set.of(BeeRoles.REQUEST_BUILDER, BeeRoles.HTTP_SEQUENCE, BeeRoles.PROCESSOR);
    private static final Pattern BASE_URL_TEMPLATE =
        Pattern.compile("\\{\\{\\s*sut\\.endpoints\\['([^']+)'\\]\\.baseUrl\\s*}}(.*)");
    private static final Pattern VARS_TEMPLATE =
        Pattern.compile("\\{\\{\\s*vars\\.([A-Za-z0-9_.-]+)\\s*}}");

    public SwarmController(ControlPlanePublisher controlPublisher,
                           ContainerLifecycleManager lifecycle,
                           SwarmOperationCoordinator operations,
                           OperationDispatchService operationDispatch,
                           SwarmLifecycleCommandService lifecycleCommands,
                           ControlResponseFactory controlResponses,
                           SwarmStore store,
                           SwarmStateStore stateStore,
                           ObjectMapper json,
                           ScenarioClient scenarios,
                           SwarmNetworkBindingService networkBindings,
                           HiveJournal hiveJournal,
                           OrchestratorAuthorization authorization,
                           FilesystemSwarmStartupArtifactStore startupArtifacts,
                           ControlPlaneProperties controlPlaneProperties) {
        this.controlPublisher = controlPublisher;
        this.lifecycle = lifecycle;
        this.operations = Objects.requireNonNull(operations, "operations");
        this.operationDispatch = Objects.requireNonNull(operationDispatch, "operationDispatch");
        this.lifecycleCommands = Objects.requireNonNull(lifecycleCommands, "lifecycleCommands");
        this.controlResponses = Objects.requireNonNull(controlResponses, "controlResponses");
        this.store = store;
        this.stateStore = stateStore;
        this.json = json;
        this.scenarios = scenarios;
        this.networkBindings = Objects.requireNonNull(networkBindings, "networkBindings");
        this.hiveJournal = Objects.requireNonNull(hiveJournal, "hiveJournal");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.startupArtifacts = Objects.requireNonNull(startupArtifacts, "startupArtifacts");
        this.originInstanceId = requireOrigin(controlPlaneProperties);
    }

    /**
     * POST {@code /api/swarms/{swarmId}/create} — bootstrap a new swarm controller container.
     * <p>
     * The request body must include {@link SwarmCreateRequest#templateId()} and an {@code idempotencyKey}.
     * Example payload:
     * <pre>{@code
     * {
     *   "templateId": "baseline-demo",
     *   "idempotencyKey": "ui-12345",
     *   "notes": "seed demo swarm"
     * }
     * }</pre>
     * We fetch the {@link SwarmTemplate}, persist the immutable filesystem startup artifact, and delegate
     * to the container lifecycle manager to start an instance. The returned {@link ControlResponse} points
     * to the authoritative operation resource and identifies its optional outcome notification topic.
     */
    @PostMapping("/{swarmId}/create")
    public ResponseEntity<?> create(@PathVariable String swarmId, @RequestBody SwarmCreateRequest req) {
        String path = "/api/swarms/" + swarmId + "/create";
        logRestRequest("POST", path, req);
        String templateId = req.templateId();
        Duration timeout = Duration.ofMillis(120_000L);
        Target operationTarget = new Target(ControlPlaneRoles.ORCHESTRATOR, originInstanceId);
        ResponseEntity<?> response;
        Optional<io.pockethive.swarm.model.lifecycle.SwarmOperation> existingOperation =
            operations.find(swarmId, OperationType.CREATE, operationTarget, req.idempotencyKey());
        if (existingOperation.isPresent()) {
            var operation = existingOperation.get();
            ControlResponse existing = controlResponse(operation, timeout.toMillis());
            log.info("[CTRL] reuse signal={} swarm={} correlation={} idempotencyKey={}",
                ControlPlaneSignals.SWARM_CREATE, swarmId, operation.correlationId(), req.idempotencyKey());
            response = ResponseEntity.accepted().body(existing);
            logRestResponse("POST", path, response);
            return response;
        }

        if (store.find(swarmId).isPresent()) {
            response = ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("Swarm '%s' already exists".formatted(swarmId)));
            logRestResponse("POST", path, response);
            return response;
        }

        String correlation = UUID.randomUUID().toString();
        response = idempotentSend(
            OperationType.CREATE, swarmId, operationTarget, req.idempotencyKey(), timeout.toMillis(), correlation, corr -> {
                ScenarioClient.ScenarioTemplateDescriptor templateDescriptor = fetchScenarioTemplate(templateId);
                requireRunTemplate(templateDescriptor);
                log.info("[CTRL] swarm-create start swarm={} templateId={} sutId={} variablesProfileId={} networkMode={} networkProfileId={} autoPullImages={} correlation={} idempotencyKey={}",
                    swarmId,
                    templateId,
                    normalize(req.sutId()),
                    normalize(req.variablesProfileId()),
                    req.networkMode(),
                    normalize(req.networkProfileId()),
                    Boolean.TRUE.equals(req.autoPullImages()),
                    corr,
                    req.idempotencyKey());
                ScenarioPlan planDescriptor = fetchScenario(templateId);
                SwarmTemplate template = planDescriptor.template();
                ScenarioPlan.Plan timeline = planDescriptor.plan();
                if (timeline == null) {
                    timeline = new ScenarioPlan.Plan(List.of(), List.of());
                }
                String image = requireImage(template, templateId);
                SwarmPlan originalPlan = planDescriptor.toSwarmPlan(swarmId);
                String scenarioVolume = io.pockethive.controlplane.filesystem.RuntimeFilesystemMount
                    .of(scenariosRuntimeRootSource)
                    .swarmVolume(swarmId, "/app/scenario", true);
                String sutId = normalize(req.sutId());
                String variablesProfileId = normalize(req.variablesProfileId());
                NetworkMode networkMode = req.networkMode();
                String networkProfileId = normalize(req.networkProfileId());
                NetworkProfile networkProfile = null;
                if (networkMode == NetworkMode.PROXIED) {
                    log.info("[CTRL] swarm-create resolve network profile swarm={} templateId={} profileId={} correlation={} idempotencyKey={}",
                        swarmId, templateId, networkProfileId, corr, req.idempotencyKey());
                    try {
                        networkProfile = scenarios.fetchNetworkProfile(networkProfileId, corr, req.idempotencyKey());
                    } catch (Exception ex) {
                        log.warn("[CTRL] swarm-create resolve network profile FAILED swarm={} templateId={} profileId={} correlation={} idempotencyKey={}",
                            swarmId, templateId, networkProfileId, corr, req.idempotencyKey(), ex);
                        throw new IllegalStateException(
                            "Failed to resolve network profile '%s'".formatted(networkProfileId), ex);
                    }
                }
                io.pockethive.swarm.model.SutEnvironment sutEnvironment = null;
                if (sutId != null) {
                    log.info("[CTRL] swarm-create resolve sut swarm={} templateId={} sutId={} correlation={} idempotencyKey={}",
                        swarmId, templateId, sutId, corr, req.idempotencyKey());
                    try {
                        sutEnvironment = scenarios.fetchScenarioSut(templateId, sutId, corr, req.idempotencyKey());
                    } catch (Exception ex) {
                        log.warn("[CTRL] swarm-create resolve sut FAILED swarm={} templateId={} sutId={} correlation={} idempotencyKey={}",
                            swarmId, templateId, sutId, corr, req.idempotencyKey(), ex);
                        throw new IllegalStateException(
                            "Failed to resolve SUT environment '%s'".formatted(sutId), ex);
                    }
                }
                final io.pockethive.swarm.model.SutEnvironment finalSutEnvironment = sutEnvironment;
                ResolvedSutEnvironment resolvedSutEnvironment =
                    networkMode == NetworkMode.PROXIED && finalSutEnvironment != null
                        ? networkBindings.resolveSutEnvironment(finalSutEnvironment, true)
                        : null;
                ScenarioClient.ResolvedVariables resolvedVariables;
                try {
                    log.info("[CTRL] swarm-create resolve variables swarm={} templateId={} profileId={} sutId={} correlation={} idempotencyKey={}",
                        swarmId, templateId, variablesProfileId, sutId, corr, req.idempotencyKey());
                    resolvedVariables = scenarios.resolveScenarioVariables(
                        templateId, variablesProfileId, sutId, corr, req.idempotencyKey());
                } catch (Exception ex) {
                    log.warn("[CTRL] swarm-create resolve variables FAILED swarm={} templateId={} profileId={} sutId={} correlation={} idempotencyKey={}",
                        swarmId, templateId, variablesProfileId, sutId, corr, req.idempotencyKey(), ex);
                    throw new IllegalStateException("Failed to resolve scenario variables", ex);
                }
                java.util.Map<String, Object> resolvedVars = resolvedVariables.vars() == null
                    ? java.util.Map.of()
                    : resolvedVariables.vars();
                int warningsCount = resolvedVariables.warnings() == null ? 0 : resolvedVariables.warnings().size();
                log.info("[CTRL] swarm-create variables resolved swarm={} templateId={} profileId={} sutId={} vars={} warnings={} correlation={} idempotencyKey={}",
                    swarmId, templateId, variablesProfileId, sutId, resolvedVars.size(), warningsCount, corr, req.idempotencyKey());
                // Resolve bee images through the same repository prefix logic used for controllers
                // so the swarm-controller sees fully-qualified image names and does not need to
                // guess registry roots. While doing so, apply any SUT-aware templates in worker
                // configuration (e.g. baseUrl: "{{ sut.endpoints['default'].baseUrl }}")
                // when a SUT environment has been bound.
                java.util.List<io.pockethive.swarm.model.Bee> rewrittenBees =
                    originalPlan.bees().stream()
                        .map(bee -> {
                            String beeImage = bee.image();
                            String resolvedImage = beeImage;
                            if (beeImage != null && !beeImage.isBlank()) {
                                String candidate = lifecycle.resolveImageForPlan(beeImage);
                                if (candidate != null && !candidate.equals(beeImage)) {
                                    resolvedImage = candidate;
                                }
                            }
                            java.util.Map<String, Object> config = bee.config();
                            if (scenarioVolume != null && !scenarioVolume.isBlank()) {
                                config = addScenarioVolume(config, scenarioVolume);
                            }
                            if (resolvedVars != null && !resolvedVars.isEmpty()) {
                                config = addScenarioVars(config, resolvedVars);
                                config = applyScenarioVarTemplates(config, resolvedVars);
                            }
                            if (finalSutEnvironment != null && isAuthSutContextImage(resolvedImage)) {
                                config = addAuthProfilePrivateContext(config, finalSutEnvironment);
                            }
                            if (finalSutEnvironment != null && config != null && !config.isEmpty()) {
                                config = applySutConfigTemplates(config, finalSutEnvironment);
                            }
                            return new io.pockethive.swarm.model.Bee(
                                bee.role(),
                                resolvedImage,
                                bee.work(),
                                bee.ports(),
                                bee.env(),
                                config);
                        })
                        .toList();
                SwarmPlan plan = new SwarmPlan(
                    originalPlan.id(),
                    rewrittenBees,
                    originalPlan.topology(),
                    originalPlan.trafficPolicy(),
                    sutId,
                    finalSutEnvironment);
                String instanceId = BeeNameGenerator.generate(ControlPlaneRoles.SWARM_CONTROLLER, swarmId);
                Map<String, Object> resolvedTimeline = json.convertValue(
                    timeline,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                boolean autoPull = Boolean.TRUE.equals(req.autoPullImages());
                if (networkMode == NetworkMode.PROXIED && resolvedSutEnvironment == null) {
                    throw new IllegalStateException(
                        "networkMode=PROXIED requires resolved SUT environment for swarm '%s'".formatted(swarmId));
                }
                prepareScenarioRuntime(templateId, swarmId);
                SwarmStartupArtifactReference startupArtifact =
                    startupArtifacts.save(swarmId, SwarmStartupArtifact.v1(plan, resolvedTimeline));
                boolean networkBindingApplied = false;
                if (networkMode == NetworkMode.PROXIED) {
                    networkBindings.applyBinding(
                        swarmId,
                        sutId,
                        networkProfileId,
                        resolvedSutEnvironment,
                        corr,
                        req.idempotencyKey(),
                        ControlPlaneRoles.ORCHESTRATOR,
                        ControlPlaneSignals.SWARM_CREATE,
                        ControlPlaneRoles.ORCHESTRATOR);
                    networkBindingApplied = true;
                }
                Swarm swarm;
                try {
                    swarm = lifecycle.startSwarm(
                        swarmId,
                        image,
                        instanceId,
                        new SwarmTemplateMetadata(
                            templateId,
                            image,
                            plan.bees(),
                            templateDescriptor.bundlePath(),
                            templateDescriptor.folderPath()),
                        autoPull,
                        sutId,
                        networkMode,
                        networkProfileId,
                        startupArtifact);
                } catch (RuntimeException ex) {
                    if (networkBindingApplied) {
                        networkBindings.rollbackBinding(
                            swarmId,
                            sutId,
                            corr,
                            req.idempotencyKey(),
                            ControlPlaneRoles.ORCHESTRATOR,
                            "swarm-create-rollback",
                            ControlPlaneRoles.ORCHESTRATOR,
                            ex);
                    }
                    throw ex;
                }
                try {
                    var data = new LinkedHashMap<String, Object>();
                    data.put("templateId", req.templateId());
                    data.put("sutId", sutId);
                    data.put("networkMode", networkMode.name());
                    data.put("networkProfileId", networkProfileId);
                    if (networkProfile != null) {
                        data.put("networkProfileName", networkProfile.name());
                    }
                    if (variablesProfileId != null) {
                        data.put("variablesProfileId", variablesProfileId);
                    }
                    data.put("autoPullImages", autoPull);
                    data.put("controllerInstance", instanceId);
                    data.put("runId", swarm.getRunId());
                    hiveJournal.append(HiveJournalEntry.info(
                        swarmId,
                        HiveJournal.Direction.LOCAL,
                        "command",
                        ControlPlaneSignals.SWARM_CREATE,
                        ControlPlaneRoles.ORCHESTRATOR,
                        ControlScope.forSwarm(swarmId),
                        corr,
                        req.idempotencyKey(),
                        null,
                        data,
                        null,
                        null));
                } catch (Exception ignore) {
                    // best-effort
                }
                if (sutId != null) {
                    swarm.setSutId(sutId);
                }
                swarm.setNetworkMode(networkMode);
                swarm.setNetworkProfileId(networkProfileId);
                if (autoPull) {
                    lifecycle.preloadSwarmImages(swarmId);
                }
            });
        logRestResponse("POST", path, response);
        return response;
    }

    /**
     * POST {@code /api/swarms/{swarmId}/start} — enable work inside an existing swarm controller.
     * <p>
     * The body accepts {@link ControlRequest}, typically {@code {"idempotencyKey":"cli-42"}}. We invoke
     * {@link #sendSignal(OperationType, String, String, long)} with {@link OperationType#START} so the swarm
     * controller converges its workers to the requested workload intent. The response identifies the
     * authoritative operation resource and its optional outcome notification topic.
     */
    @PostMapping("/{swarmId}/start")
    public ResponseEntity<ControlResponse> start(@PathVariable String swarmId, @RequestBody ControlRequest req) {
        String path = "/api/swarms/" + swarmId + "/start";
        logRestRequest("POST", path, req);
        requireRunSwarm(swarmId);
        ResponseEntity<ControlResponse> response = sendSignal(OperationType.START, swarmId, req.idempotencyKey(), 180_000L);
        logRestResponse("POST", path, response);
        return response;
    }

    /**
     * POST {@code /api/swarms/{swarmId}/stop} — pause workload processing.
     * <p>
     * Sends a {@code swarm-stop} control signal that causes the swarm controller to publish
     * {@code config-update(enabled=false)} and eventually emit the terminal {@code swarm-stop} outcome. Clients can reuse
     * the idempotency key when retrying to guarantee the same correlation id.
     */
    @PostMapping("/{swarmId}/stop")
    public ResponseEntity<ControlResponse> stop(@PathVariable String swarmId, @RequestBody ControlRequest req) {
        String path = "/api/swarms/" + swarmId + "/stop";
        logRestRequest("POST", path, req);
        requireManageSwarm(swarmId);
        ResponseEntity<ControlResponse> response = sendSignal(OperationType.STOP, swarmId, req.idempotencyKey(), 90_000L);
        logRestResponse("POST", path, response);
        return response;
    }

    /**
     * POST {@code /api/swarms/{swarmId}/remove} — delete queues and terminate the swarm controller.
     * <p>
     * The Orchestrator persists the correlation-specific filesystem request before sending the repeatable
     * wake-up signal. It completes the authoritative operation only after validating the matching filesystem
     * result and removing the controller runtime; callers follow the returned operation URL.
     */
    @PostMapping("/{swarmId}/remove")
    public ResponseEntity<ControlResponse> remove(@PathVariable String swarmId, @RequestBody ControlRequest req) {
        String path = "/api/swarms/" + swarmId + "/remove";
        logRestRequest("POST", path, req);
        requireManageSwarm(swarmId);
        ResponseEntity<ControlResponse> response = sendSignal(OperationType.REMOVE, swarmId, req.idempotencyKey(), 180_000L);
        logRestResponse("POST", path, response);
        return response;
    }

    @PostMapping("/{swarmId}/network")
    public ResponseEntity<?> updateNetwork(@PathVariable String swarmId, @RequestBody SwarmNetworkUpdateRequest req) {
        String path = "/api/swarms/" + swarmId + "/network";
        logRestRequest("POST", path, req);
        requireManageSwarm(swarmId);
        ResponseEntity<?> response;
        Optional<Swarm> swarmOpt = store.find(swarmId);
        if (swarmOpt.isEmpty()) {
            response = ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("Swarm '%s' was not found".formatted(swarmId)));
            logRestResponse("POST", path, response);
            return response;
        }

        Swarm swarm = swarmOpt.get();
        String sutId = normalize(swarm.getSutId());
        if (sutId == null) {
            response = ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("Swarm '%s' has no bound sutId".formatted(swarmId)));
            logRestResponse("POST", path, response);
            return response;
        }

        String correlationId = UUID.randomUUID().toString();
        String idempotencyKey = normalize(req.idempotencyKey());
        String reason = normalize(req.notes()) == null ? "swarm-network-update" : req.notes().trim();
        if (req.networkMode() == NetworkMode.DIRECT) {
            NetworkBinding cleared = networkBindings.clearBinding(
                swarmId,
                sutId,
                correlationId,
                idempotencyKey,
                "hive",
                reason,
                "hive");
            swarm.setNetworkMode(NetworkMode.DIRECT);
            swarm.setNetworkProfileId(null);
            networkBindings.publishControllerNetworkContext(
                swarm,
                sutId,
                NetworkMode.DIRECT,
                null,
                correlationId,
                idempotencyKey);
            response = ResponseEntity.ok(cleared);
            logRestResponse("POST", path, response);
            return response;
        }

        String templateId = normalize(swarm.templateId());
        if (templateId == null) {
            response = ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("Swarm '%s' has no template metadata".formatted(swarmId)));
            logRestResponse("POST", path, response);
            return response;
        }

        String networkProfileId = normalize(req.networkProfileId());
        fetchNetworkProfile(networkProfileId, templateId, swarmId, correlationId, idempotencyKey);
        SutEnvironment sutEnvironment = fetchSutEnvironment(templateId, sutId, swarmId, correlationId, idempotencyKey);
        ResolvedSutEnvironment resolvedSutEnvironment = networkBindings.resolveSutEnvironment(sutEnvironment, true);
        NetworkBinding binding = networkBindings.applyBinding(
            swarmId,
            sutId,
            networkProfileId,
            resolvedSutEnvironment,
            correlationId,
            idempotencyKey,
            "hive",
            reason,
            "hive");
        swarm.setNetworkMode(NetworkMode.PROXIED);
        swarm.setNetworkProfileId(networkProfileId);
        networkBindings.publishControllerNetworkContext(
            swarm,
            sutId,
            NetworkMode.PROXIED,
            networkProfileId,
            correlationId,
            idempotencyKey);
        response = ResponseEntity.ok(binding);
        logRestResponse("POST", path, response);
        return response;
    }

    /**
     * Wrap common send logic for lifecycle signals ({@code swarm-start|stop|remove}).
     * <p>
     * The lifecycle command service reserves the operation before dispatching the signal, then completes
     * it only after the controller reports the required fresh convergence. The timeout is expressed in
     * milliseconds for alignment with the REST contract.
     */
    private ResponseEntity<ControlResponse> sendSignal(
        OperationType operationType, String swarmId, String idempotencyKey, long timeoutMs) {
        String signal = ControlPlaneOperations.signalForType(operationType);
        var reservation = lifecycleCommands.dispatch(
            operationType, swarmId, idempotencyKey, Duration.ofMillis(timeoutMs));
        if (reservation.reused()) {
            log.info("[CTRL] reuse signal={} swarm={} correlation={} idempotencyKey={}",
                signal, swarmId, reservation.operation().correlationId(), idempotencyKey);
        } else {
            log.info("[CTRL] issue signal={} swarm={} correlation={} idempotencyKey={} timeoutMs={}",
                signal, swarmId, reservation.operation().correlationId(), idempotencyKey, timeoutMs);
        }
        return ResponseEntity.accepted().body(controlResponse(reservation.operation(), timeoutMs));
    }

    /**
     * Apply the orchestrator's idempotency contract to an outgoing signal.
     * <p>
     * If the operation coordinator already contains the exact target-specific logical request, we
     * return its existing {@link ControlResponse}. Otherwise the shared dispatch service reserves the
     * operation before {@code action.accept(corr)} publishes the control message.
     */
    private ResponseEntity<ControlResponse> idempotentSend(OperationType operationType, String swarmId, Target target, String idempotencyKey,
                                                           long timeoutMs, java.util.function.Consumer<String> action) {
        return idempotentSend(operationType, swarmId, target, idempotencyKey, timeoutMs, UUID.randomUUID().toString(), action);
    }

    private ResponseEntity<ControlResponse> idempotentSend(OperationType operationType, String swarmId, Target target, String idempotencyKey,
                                                           long timeoutMs, String correlation,
                                                           java.util.function.Consumer<String> action) {
        String signal = ControlPlaneOperations.signalForType(operationType);
        var reservation = operationDispatch.dispatch(
            swarmId,
            operationType,
            target,
            correlation,
            idempotencyKey,
            Duration.ofMillis(timeoutMs),
            action);
        if (reservation.reused()) {
            var existing = reservation.operation();
            log.info("[CTRL] reuse signal={} swarm={} correlation={} idempotencyKey={}",
                signal, swarmId, existing.correlationId(), idempotencyKey);
            return ResponseEntity.accepted().body(controlResponse(existing, timeoutMs));
        }

        ControlResponse resp = controlResponse(reservation.operation(), timeoutMs);
        log.info("[CTRL] issue signal={} swarm={} correlation={} idempotencyKey={} timeoutMs={}",
            signal, swarmId, correlation, idempotencyKey, timeoutMs);
        return ResponseEntity.accepted().body(resp);
    }

    /**
     * Build the canonical asynchronous-operation response.
     * <p>
     * The operation URL is authoritative. The orchestrator-owned outcome topic is a notification channel
     * carrying the same terminal result and is documented in {@code docs/ORCHESTRATOR-REST.md#control-response}.
     */
    private ControlResponse controlResponse(io.pockethive.swarm.model.lifecycle.SwarmOperation operation, long timeoutMs) {
        return controlResponses.create(operation, Duration.ofMillis(timeoutMs));
    }

    /**
     * Retrieve the requested swarm template from the scenario service.
     * <p>
     * When a template cannot be resolved we raise an {@link IllegalStateException} so the API returns
     * HTTP 500 and the UI can surface a friendly error. Logs include the template id for quick lookup.
     */
    private ScenarioPlan fetchScenario(String templateId) {
        try {
            ScenarioPlan plan = scenarios.fetchScenario(templateId);
            if (plan == null || plan.template() == null) {
                throw new IllegalStateException("Template %s was not found".formatted(templateId));
            }
            return plan;
        } catch (Exception e) {
            log.warn("failed to fetch template {}", templateId, e);
            throw new IllegalStateException("Failed to fetch template %s".formatted(templateId), e);
        }
    }

    private ScenarioClient.ScenarioTemplateDescriptor fetchScenarioTemplate(String templateId) {
        try {
            ScenarioClient.ScenarioTemplateDescriptor descriptor = scenarios.fetchScenarioTemplate(templateId);
            if (descriptor == null || descriptor.id() == null || descriptor.id().isBlank()) {
                throw new IllegalStateException("Template %s metadata was not found".formatted(templateId));
            }
            return descriptor;
        } catch (Exception e) {
            log.warn("failed to fetch template metadata {}", templateId, e);
            throw new IllegalStateException("Failed to fetch template metadata %s".formatted(templateId), e);
        }
    }

    private String prepareScenarioRuntime(String templateId, String swarmId) {
        try {
            String runtimeDir = scenarios.prepareScenarioRuntime(templateId, swarmId);
            if (runtimeDir == null || runtimeDir.isBlank()) {
                throw new IllegalStateException(
                    "Scenario runtime for template %s and swarm %s returned empty path".formatted(templateId, swarmId));
            }
            return runtimeDir;
        } catch (ScenarioClientException e) {
            log.warn("scenario manager refused runtime preparation for template {} and swarm {}", templateId, swarmId, e);
            throw e;
        } catch (Exception e) {
            log.warn("failed to prepare scenario runtime for template {} and swarm {}", templateId, swarmId, e);
            throw new IllegalStateException(
                "Failed to prepare scenario runtime for template %s".formatted(templateId), e);
        }
    }

    @ExceptionHandler(ScenarioClientException.class)
    ResponseEntity<?> scenarioManagerFailure(ScenarioClientException e) {
        String body = e.responseBody();
        if (body == null || body.isBlank()) {
            return ResponseEntity.status(e.statusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(e.getMessage()));
        }
        MediaType contentType = responseContentType(e.contentType(), body);
        return ResponseEntity.status(e.statusCode())
            .contentType(contentType)
            .body(body);
    }

    private static MediaType responseContentType(String contentType, String body) {
        if (contentType != null && !contentType.isBlank()) {
            try {
                return MediaType.parseMediaType(contentType);
            } catch (RuntimeException ignored) {
                // Fall through to body-based detection.
            }
        }
        String trimmed = body == null ? "" : body.trim();
        return trimmed.startsWith("{") || trimmed.startsWith("[")
            ? MediaType.APPLICATION_JSON
            : MediaType.TEXT_PLAIN;
    }

    /**
     * Validate that the template specifies a swarm-controller image.
     * <p>
     * Templates are considered invalid without an image because we cannot launch the controller container.
     */
    private static String requireImage(SwarmTemplate template, String templateId) {
        String image = template.image();
        if (image == null || image.isBlank()) {
            throw new IllegalStateException("Template %s missing swarm-controller image".formatted(templateId));
        }
        return image;
    }

    private static Map<String, Object> addScenarioVolume(Map<String, Object> config, String volumeSpec) {
        if (volumeSpec == null || volumeSpec.isBlank()) {
            return config;
        }
        Map<String, Object> result = (config == null || config.isEmpty())
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(config);

        Object dockerObj = result.get("docker");
        Map<String, Object> docker = new LinkedHashMap<>();
        if (dockerObj instanceof Map<?, ?> dockerRaw) {
            dockerRaw.forEach((key, value) -> {
                if (key != null) {
                    docker.put(key.toString(), value);
                }
            });
        }

        List<String> volumes = new java.util.ArrayList<>();
        Object volumesObj = docker.get("volumes");
        if (volumesObj instanceof List<?> raw) {
            for (Object entry : raw) {
                if (entry instanceof String s) {
                    String trimmed = s.trim();
                    if (!trimmed.isEmpty()) {
                        volumes.add(trimmed);
                    }
                }
            }
        }

        String trimmedSpec = volumeSpec.trim();
        boolean alreadyPresent = volumes.stream().anyMatch(v -> v.equals(trimmedSpec));
        if (!alreadyPresent) {
            volumes.add(trimmedSpec);
        }

        docker.put("volumes", List.copyOf(volumes));
        result.put("docker", Map.copyOf(docker));
        return Map.copyOf(result);
    }

    private static Map<String, Object> addScenarioVars(Map<String, Object> config, Map<String, Object> vars) {
        if (vars == null || vars.isEmpty()) {
            return config;
        }
        Map<String, Object> result = (config == null || config.isEmpty())
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(config);
        // Reserved key for scenario variables: config.vars (map).
        // Workers are expected to propagate this into the WorkItem headers so Pebble/SpEL templates can reference it as `vars.*`.
        result.put("vars", vars);
        return Map.copyOf(result);
    }

    private static Map<String, Object> applyScenarioVarTemplates(Map<String, Object> config, Map<String, Object> vars) {
        if (config == null || config.isEmpty() || vars == null || vars.isEmpty()) {
            return config;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        config.forEach((key, value) -> {
            if ("vars".equals(key)) {
                result.put(key, value);
            } else {
                result.put(key, renderScenarioVarValue(value, vars));
            }
        });
        return Map.copyOf(result);
    }

    private static Object renderScenarioVarValue(Object value, Map<String, Object> vars) {
        if (value instanceof String text) {
            return renderScenarioVarString(text, vars);
        }
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> rendered = new LinkedHashMap<>();
            rawMap.forEach((key, nestedValue) -> {
                if (key != null) {
                    rendered.put(key.toString(), renderScenarioVarValue(nestedValue, vars));
                }
            });
            return Map.copyOf(rendered);
        }
        if (value instanceof List<?> rawList) {
            return rawList.stream()
                .map(item -> renderScenarioVarValue(item, vars))
                .toList();
        }
        return value;
    }

    private static Object renderScenarioVarString(String text, Map<String, Object> vars) {
        Matcher exact = VARS_TEMPLATE.matcher(text.trim());
        if (exact.matches()) {
            return resolveScenarioVar(vars, exact.group(1));
        }

        Matcher matcher = VARS_TEMPLATE.matcher(text);
        StringBuffer rendered = new StringBuffer();
        boolean changed = false;
        while (matcher.find()) {
            Object value = resolveScenarioVar(vars, matcher.group(1));
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(Objects.toString(value, "")));
            changed = true;
        }
        if (!changed) {
            return text;
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    @SuppressWarnings("unchecked")
    private static Object resolveScenarioVar(Map<String, Object> vars, String path) {
        Object current = vars;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> rawMap) || !rawMap.containsKey(part)) {
                throw new IllegalStateException("Unknown scenario variable '%s'".formatted(path));
            }
            current = ((Map<String, Object>) rawMap).get(part);
        }
        if (current == null) {
            throw new IllegalStateException("Scenario variable '%s' is null".formatted(path));
        }
        return current;
    }

    private static Map<String, Object> addAuthProfilePrivateContext(Map<String, Object> config, SutEnvironment sutEnvironment) {
        Map<String, Object> result = (config == null || config.isEmpty())
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(config);
        Map<String, Object> privateConfig = new LinkedHashMap<>();
        Object existingPrivateConfig = result.get(BeeConfigKeys.PRIVATE_CONFIG);
        if (existingPrivateConfig instanceof Map<?, ?> rawPrivateConfig) {
            rawPrivateConfig.forEach((key, value) -> {
                if (key != null) {
                    privateConfig.put(key.toString(), value);
                }
            });
        }
        Map<String, Object> authProfileContext = Map.of(BeeConfigKeys.SUT, sutContext(sutEnvironment));
        privateConfig.put(BeeConfigKeys.AUTH_PROFILE, authProfileContext);
        result.put(BeeConfigKeys.PRIVATE_CONFIG, Map.copyOf(privateConfig));
        return Map.copyOf(result);
    }

    private static Map<String, Object> sutContext(SutEnvironment sutEnvironment) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", sutEnvironment.id());
        result.put("name", sutEnvironment.name());
        if (sutEnvironment.type() != null && !sutEnvironment.type().isBlank()) {
            result.put("type", sutEnvironment.type());
        }
        Map<String, Object> endpoints = new LinkedHashMap<>();
        sutEnvironment.endpoints().forEach((key, endpoint) -> endpoints.put(key, sutEndpointContext(endpoint)));
        result.put("endpoints", Map.copyOf(endpoints));
        return Map.copyOf(result);
    }

    private static Map<String, Object> sutEndpointContext(SutEndpoint endpoint) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", endpoint.kind());
        result.put("baseUrl", endpoint.baseUrl());
        if (endpoint.upstreamBaseUrl() != null && !endpoint.upstreamBaseUrl().isBlank()) {
            result.put("upstreamBaseUrl", endpoint.upstreamBaseUrl());
        }
        return Map.copyOf(result);
    }

    private NetworkProfile fetchNetworkProfile(String networkProfileId,
                                               String templateId,
                                               String swarmId,
                                               String correlationId,
                                               String idempotencyKey) {
        log.info("[CTRL] swarm-network resolve network profile swarm={} templateId={} profileId={} correlation={} idempotencyKey={}",
            swarmId, templateId, networkProfileId, correlationId, idempotencyKey);
        try {
            return scenarios.fetchNetworkProfile(networkProfileId, correlationId, idempotencyKey);
        } catch (Exception ex) {
            log.warn("[CTRL] swarm-network resolve network profile FAILED swarm={} templateId={} profileId={} correlation={} idempotencyKey={}",
                swarmId, templateId, networkProfileId, correlationId, idempotencyKey, ex);
            throw new IllegalStateException(
                "Failed to resolve network profile '%s'".formatted(networkProfileId), ex);
        }
    }

    private SutEnvironment fetchSutEnvironment(String templateId,
                                               String sutId,
                                               String swarmId,
                                               String correlationId,
                                               String idempotencyKey) {
        log.info("[CTRL] swarm-network resolve sut swarm={} templateId={} sutId={} correlation={} idempotencyKey={}",
            swarmId, templateId, sutId, correlationId, idempotencyKey);
        try {
            return scenarios.fetchScenarioSut(templateId, sutId, correlationId, idempotencyKey);
        } catch (Exception ex) {
            log.warn("[CTRL] swarm-network resolve sut FAILED swarm={} templateId={} sutId={} correlation={} idempotencyKey={}",
                swarmId, templateId, sutId, correlationId, idempotencyKey, ex);
            throw new IllegalStateException(
                "Failed to resolve SUT environment '%s'".formatted(sutId), ex);
        }
    }

    public record SwarmNetworkUpdateRequest(NetworkMode networkMode,
                                            String networkProfileId,
                                            String idempotencyKey,
                                            String notes) {
        public SwarmNetworkUpdateRequest {
            if (networkMode == null) {
                throw new IllegalArgumentException("networkMode must be provided");
            }
            networkProfileId = normalize(networkProfileId);
            idempotencyKey = normalize(idempotencyKey);
            notes = normalize(notes);
            if (networkMode == NetworkMode.DIRECT && networkProfileId != null) {
                throw new IllegalArgumentException("networkProfileId requires networkMode=PROXIED");
            }
            if (networkMode == NetworkMode.PROXIED && networkProfileId == null) {
                throw new IllegalArgumentException("networkProfileId must be provided when networkMode=PROXIED");
            }
        }
    }

    private record ErrorResponse(String message) {}

    /**
     * GET {@code /api/swarms} — list swarms from cached swarm-controller status-full snapshots.
     * <p>
     * This endpoint intentionally reflects the orchestrator cache only: swarms without a cached
     * swarm-controller status-full snapshot are omitted.
     * <pre>{@code
     * [
     *   {
     *     "id": "demo",
     *     "status": "RUNNING",
     *     "templateId": "baseline-demo",
     *     "controllerImage": "ghcr.io/pockethive/swarm-controller:1.2.3",
     *     "bees": [ { "role": "generator", "image": "ghcr.io/..." } ]
     *   }
     * ]
     * }</pre>
     */
    @GetMapping
    public ResponseEntity<List<SwarmStateView>> list() {
        String path = "/api/swarms";
        logRestRequest("GET", path, null);
        List<SwarmStateView> payload = store.all().stream()
            .filter(this::canReadSwarm)
            .sorted(Comparator.comparing(Swarm::getId))
            .map(this::toStateView)
            .toList();
        ResponseEntity<List<SwarmStateView>> response = ResponseEntity.ok(payload);
        logRestResponse("GET", path, response);
        return response;
    }

    /**
     * GET {@code /api/swarms/{swarmId}} — fetch the latest cached swarm-controller status-full snapshot.
     */
    @GetMapping("/{swarmId}")
    public ResponseEntity<SwarmStateView> view(@PathVariable String swarmId) {
        String path = "/api/swarms/" + swarmId;
        logRestRequest("GET", path, null);
        ResponseEntity<SwarmStateView> response;
        Optional<Swarm> swarmOpt = store.find(swarmId);
        if (swarmOpt.isEmpty()) {
            response = ResponseEntity.notFound().build();
        } else {
            Swarm swarm = swarmOpt.get();
            requireReadSwarm(swarm);
            response = ResponseEntity.ok(toStateView(swarm));
        }
        logRestResponse("GET", path, response);
        return response;
    }

    @GetMapping("/{swarmId}/operations/{correlationId}")
    public ResponseEntity<io.pockethive.swarm.model.lifecycle.SwarmOperation> operation(
        @PathVariable String swarmId,
        @PathVariable String correlationId) {
        return operations.findByCorrelation(correlationId)
            .filter(operation -> operation.swarmId().equals(swarmId))
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private boolean canReadSwarm(Swarm swarm) {
        AuthenticatedUserDto user = currentUser();
        if (user == null) {
            return true;
        }
        return authorization.canRead(user, resolveTemplateMetadata(swarm));
    }

    private void requireReadSwarm(Swarm swarm) {
        AuthenticatedUserDto user = currentUser();
        if (user == null) {
            return;
        }
        if (!authorization.canRead(user, resolveTemplateMetadata(swarm))) {
            throw new org.springframework.web.server.ResponseStatusException(
                HttpStatus.FORBIDDEN,
                authorization.readDeniedMessage());
        }
    }

    private void requireRunSwarm(String swarmId) {
        AuthenticatedUserDto user = currentUser();
        if (user == null) {
            return;
        }
        Swarm swarm = store.find(swarmId)
            .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!authorization.canRun(user, resolveTemplateMetadata(swarm))) {
            throw new org.springframework.web.server.ResponseStatusException(
                HttpStatus.FORBIDDEN,
                authorization.runDeniedMessage());
        }
    }

    private void requireManageSwarm(String swarmId) {
        AuthenticatedUserDto user = currentUser();
        if (user == null) {
            return;
        }
        Swarm swarm = store.find(swarmId)
            .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!authorization.canManage(user, resolveTemplateMetadata(swarm))) {
            throw new org.springframework.web.server.ResponseStatusException(
                HttpStatus.FORBIDDEN,
                authorization.manageDeniedMessage());
        }
    }

    private void requireRunTemplate(ScenarioClient.ScenarioTemplateDescriptor templateDescriptor) {
        AuthenticatedUserDto user = currentUser();
        if (templateDescriptor != null && templateDescriptor.defunct()) {
            throw new org.springframework.web.server.ResponseStatusException(
                HttpStatus.CONFLICT,
                "Scenario template is defunct and cannot be launched");
        }
        if (user == null) {
            return;
        }
        if (!authorization.canRun(user, templateDescriptor)) {
            throw new org.springframework.web.server.ResponseStatusException(
                HttpStatus.FORBIDDEN,
                authorization.runDeniedMessage());
        }
    }

    private SwarmTemplateMetadata resolveTemplateMetadata(Swarm swarm) {
        SwarmTemplateMetadata metadata = swarm.templateMetadata();
        if (metadata == null) {
            return null;
        }
        if (metadata.bundlePath() != null && !metadata.bundlePath().isBlank()) {
            return metadata;
        }
        String templateId = metadata.templateId();
        if (templateId == null || templateId.isBlank()) {
            return metadata;
        }
        ScenarioClient.ScenarioTemplateDescriptor descriptor = fetchScenarioTemplate(templateId);
        SwarmTemplateMetadata resolved = new SwarmTemplateMetadata(
            metadata.templateId(),
            metadata.controllerImage(),
            metadata.bees(),
            descriptor.bundlePath(),
            descriptor.folderPath());
        swarm.attachTemplate(resolved);
        return resolved;
    }

    private AuthenticatedUserDto currentUser() {
        return OrchestratorCurrentUserHolder.get();
    }

    private Map<String, Object> runtimeMeta(String swarmId) {
        Swarm swarm = store.find(swarmId)
            .orElseThrow(() -> new IllegalStateException("Swarm is not registered: " + swarmId));
        return Map.of("templateId", swarm.templateId(), "runId", swarm.getRunId());
    }

    private SwarmStateView toStateView(Swarm swarm) {
        Instant observedAt = swarm.getControllerStatusReceivedAt();
        boolean stale = observedAt == null || Instant.now().isAfter(observedAt.plus(STATUS_FULL_STALE_AFTER));
        return new SwarmStateView(
            swarm.getId(),
            swarm.getRunId(),
            swarm.getRuntimeIntent(),
            swarm.getWorkloadIntent(),
            swarm.getControllerState(),
            swarm.getWorkloadState(),
            swarm.getHealth(),
            swarm.getRuntimeResourceState(),
            observedAt,
            stale,
            operations.activeLifecycle(swarm.getId()).orElse(null),
            swarm.templateId(),
            swarm.controllerImage(),
            workerSummaries(swarm.getObservation()),
            swarm.getObservation());
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null || field == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText(null);
        return text == null || text.isBlank() ? null : text;
    }

    private List<WorkerSummary> workerSummaries(Map<String, Object> observation) {
        JsonNode workersNode = json.valueToTree(observation == null ? Map.of() : observation).path("workers");
        if (workersNode == null || workersNode.isMissingNode() || !workersNode.isArray()) {
            return List.of();
        }
        List<WorkerSummary> workers = new ArrayList<>();
        for (JsonNode workerNode : workersNode) {
            String role = textOrNull(workerNode, "role");
            String instance = textOrNull(workerNode, "instance");
            if (role == null || instance == null) {
                continue;
            }
            JsonNode runtime = workerNode.path("runtime");
            String image = runtime != null && runtime.isObject() ? textOrNull(runtime, "image") : null;
            workers.add(new WorkerSummary(role, instance, image));
        }
        return List.copyOf(workers);
    }

    /**
     * Serialize control signals for RabbitMQ publishing.
     * <p>
     * Wraps {@link ObjectMapper#writeValueAsString(Object)} and rethrows as {@link IllegalStateException}
     * so REST handlers surface a 500 if serialization fails.
     */
    private String toJson(ControlSignal signal) {
        return ControlPlaneJson.write(
            signal,
            "control signal %s for swarm %s".formatted(
                signal.type(), signal.scope() != null ? signal.scope().swarmId() : "n/a"));
    }

    /**
     * Emit a control-plane message and log the human-readable context.
     * <p>
     * Example log line: {@code [CTRL] SEND swarm-start rk=signal.swarm-start.demo payload={...}}. Having the
     * snippet in logs helps engineers correlate REST calls to RabbitMQ traffic when debugging.
     */
    private void sendControl(String routingKey, String payload, String context) {
        String label = (context == null || context.isBlank()) ? "SEND" : "SEND " + context;
        log.info("[CTRL] {} rk={} payload={}", label, routingKey, snippet(payload));
        if (routingKey != null && routingKey.startsWith("signal.")) {
            controlPublisher.publishSignal(new SignalMessage(routingKey, payload));
        } else {
            controlPublisher.publishEvent(new EventMessage(routingKey, payload));
        }
    }

    private static String requireOrigin(ControlPlaneProperties properties) {
        String instanceId = properties.getInstanceId();
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalStateException("pockethive.control-plane.identity.instance-id must not be null or blank");
        }
        return instanceId.trim();
    }

    /**
     * Structured logging for incoming REST requests, trimming large bodies for readability.
     */
    private void logRestRequest(String method, String path, Object body) {
        log.info("[REST] {} {} request={}", method, path, toSafeString(body));
    }

    /**
     * Structured logging for REST responses so correlation id and status codes appear together.
     */
    private void logRestResponse(String method, String path, ResponseEntity<?> response) {
        if (response == null) {
            return;
        }
        log.info("[REST] {} {} -> status={} body={}", method, path, response.getStatusCode(), toSafeString(response.getBody()));
    }

    /**
     * Render request/response bodies safely for logs (max 300 characters).
     */
    private static String toSafeString(Object value) {
        if (value == null) {
            return "";
        }
        String text = value.toString();
        if (text.length() > 300) {
            return text.substring(0, 300) + "…";
        }
        return text;
    }

    /**
     * Shorten AMQP payloads for logging while preserving leading JSON context.
     */
    private static String snippet(String payload) {
        if (payload == null) {
            return "";
        }
        String trimmed = payload.strip();
        if (trimmed.length() > 300) {
            return trimmed.substring(0, 300) + "…";
        }
        return trimmed;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean isAuthSutContextImage(String image) {
        String repositoryName = imageRepositoryName(image);
        return repositoryName != null && AUTH_SUT_CONTEXT_IMAGE_NAMES.contains(repositoryName);
    }

    private static String imageRepositoryName(String image) {
        String normalized = normalize(image);
        if (normalized == null) {
            return null;
        }
        int digestIndex = normalized.indexOf('@');
        String withoutDigest = digestIndex >= 0 ? normalized.substring(0, digestIndex) : normalized;
        int slashIndex = withoutDigest.lastIndexOf('/');
        String lastSegment = slashIndex >= 0 ? withoutDigest.substring(slashIndex + 1) : withoutDigest;
        int tagIndex = lastSegment.lastIndexOf(':');
        String repositoryName = tagIndex >= 0 ? lastSegment.substring(0, tagIndex) : lastSegment;
        return normalize(repositoryName);
    }

    /**
     * Applies SUT-aware config templating for a single worker configuration map.
     * <p>
     * For now this is deliberately small and explicit:
     * <ul>
     *   <li>If {@code config.baseUrl} is a plain string, it is left unchanged.</li>
     *   <li>If {@code config.baseUrl} matches
     *       {@code {{ sut.endpoints['<id>'].baseUrl }}<suffix>},
     *       we resolve {@code <id>} against the bound {@link SutEnvironment} and replace the
     *       entire value with {@code endpoint.baseUrl + suffix}.</li>
     *   <li>If a template is present but no SUT environment was bound, or the endpoint cannot
     *       be resolved, we fail fast with an {@link IllegalStateException}.</li>
     * </ul>
     */
    private static Map<String, Object> applySutConfigTemplates(Map<String, Object> config, SutEnvironment sutEnvironment) {
        Object baseUrlObj = config.get("baseUrl");
        if (!(baseUrlObj instanceof String template)) {
            return config;
        }
        Matcher matcher = BASE_URL_TEMPLATE.matcher(template.trim());
        if (!matcher.matches()) {
            return config;
        }
        if (sutEnvironment == null) {
            throw new IllegalStateException("SUT-aware baseUrl template used but no sutId was provided");
        }
        String endpointId = matcher.group(1);
        String suffix = matcher.group(2);
        SutEndpoint endpoint = sutEnvironment.endpoints().get(endpointId);
        if (endpoint == null) {
            throw new IllegalStateException("Unknown SUT endpoint '%s' for environment '%s'"
                .formatted(endpointId, sutEnvironment.id()));
        }
        String base = endpoint.baseUrl();
        if (base == null || base.isBlank()) {
            throw new IllegalStateException("SUT endpoint '%s' for environment '%s' has no baseUrl"
                .formatted(endpointId, sutEnvironment.id()));
        }
        String resolved = base.trim() + suffix;
        Map<String, Object> updated = new java.util.LinkedHashMap<>(config);
        updated.put("baseUrl", resolved);
        return Map.copyOf(updated);
    }
}
