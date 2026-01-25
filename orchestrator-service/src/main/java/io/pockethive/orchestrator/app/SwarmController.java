package io.pockethive.orchestrator.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlScope;
import io.pockethive.control.ControlSignal;
import io.pockethive.control.ConfirmationScope;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.messaging.ControlSignals;
import io.pockethive.controlplane.messaging.EventMessage;
import io.pockethive.controlplane.messaging.SignalMessage;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.spring.ControlPlaneProperties;
import io.pockethive.observability.ControlPlaneJson;
import io.pockethive.orchestrator.domain.IdempotencyStore;
import io.pockethive.orchestrator.domain.ScenarioPlan;
import io.pockethive.orchestrator.domain.ScenarioTimelineRegistry;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmCreateRequest;
import io.pockethive.orchestrator.domain.SwarmCreateTracker;
import io.pockethive.orchestrator.domain.SwarmCreateTracker.Pending;
import io.pockethive.orchestrator.domain.SwarmCreateTracker.Phase;
import io.pockethive.orchestrator.domain.HiveJournal;
import io.pockethive.orchestrator.domain.HiveJournal.HiveJournalEntry;
import io.pockethive.orchestrator.domain.SwarmHealth;
import io.pockethive.orchestrator.domain.SwarmPlanRegistry;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import io.pockethive.orchestrator.domain.SwarmStatus;
import io.pockethive.orchestrator.domain.SwarmTemplateMetadata;
import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.SwarmPlan;
import io.pockethive.swarm.model.SwarmTemplate;
import io.pockethive.swarm.model.SutEndpoint;
import io.pockethive.swarm.model.SutEnvironment;
import io.pockethive.swarm.model.TrafficPolicy;
import io.pockethive.util.BeeNameGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import org.springframework.http.ResponseEntity;
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
    private final SwarmCreateTracker creates;
    private final IdempotencyStore idempotency;
    private final SwarmRegistry registry;
    private final SwarmPlanRegistry plans;
    private final ScenarioTimelineRegistry timelines;
    private final ScenarioClient scenarios;
    private final HiveJournal hiveJournal;
    private final ObjectMapper json;
    private final String originInstanceId;
    @Value("${POCKETHIVE_SCENARIOS_RUNTIME_ROOT:}")
    private String scenariosRuntimeRootSource;
    private static final String CREATE_LOCK_KEY = "__create-lock__";
    private static final Pattern BASE_URL_TEMPLATE =
        Pattern.compile("\\{\\{\\s*sut\\.endpoints\\['([^']+)'\\]\\.baseUrl\\s*}}(.*)");

    public SwarmController(ControlPlanePublisher controlPublisher,
                           ContainerLifecycleManager lifecycle,
                           SwarmCreateTracker creates,
                           IdempotencyStore idempotency,
                           SwarmRegistry registry,
                           ObjectMapper json,
                           ScenarioClient scenarios,
                           HiveJournal hiveJournal,
                           SwarmPlanRegistry plans,
                           ScenarioTimelineRegistry timelines,
                           ControlPlaneProperties controlPlaneProperties) {
        this.controlPublisher = controlPublisher;
        this.lifecycle = lifecycle;
        this.creates = creates;
        this.idempotency = idempotency;
        this.registry = registry;
        this.json = json;
        this.scenarios = scenarios;
        this.hiveJournal = Objects.requireNonNull(hiveJournal, "hiveJournal");
        this.plans = plans;
        this.timelines = timelines;
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
     * We fetch the {@link SwarmTemplate}, record the plan, and delegate to the container lifecycle manager
     * to start an instance. A {@link ControlResponse} containing correlation and watch routing keys is
     * returned so callers can poll RabbitMQ for confirmation events.
     */
    @PostMapping("/{swarmId}/create")
    public ResponseEntity<?> create(@PathVariable String swarmId, @RequestBody SwarmCreateRequest req) {
        String path = "/api/swarms/" + swarmId + "/create";
        logRestRequest("POST", path, req);
        Duration timeout = Duration.ofMillis(120_000L);
        ResponseEntity<?> response;
        Optional<String> existingCorrelation = idempotency.findCorrelation(swarmId, "swarm-create", req.idempotencyKey());
        if (existingCorrelation.isPresent()) {
            String correlation = existingCorrelation.get();
            ControlResponse existing = new ControlResponse(correlation, req.idempotencyKey(),
                watchFor("swarm-create", swarmId), timeout.toMillis());
            log.info("[CTRL] reuse signal={} swarm={} correlation={} idempotencyKey={}",
                "swarm-create", swarmId, correlation, req.idempotencyKey());
            response = ResponseEntity.accepted().body(existing);
            logRestResponse("POST", path, response);
            return response;
        }

        if (registry.find(swarmId).isPresent()) {
            response = ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("Swarm '%s' already exists".formatted(swarmId)));
            logRestResponse("POST", path, response);
            return response;
        }

        String lockCorrelation = UUID.randomUUID().toString();
        Optional<String> lockOwner = idempotency.reserve(swarmId, "swarm-create", CREATE_LOCK_KEY, lockCorrelation);
        if (lockOwner.isPresent()) {
            String corr = lockOwner.get();
            idempotency.reserve(swarmId, "swarm-create", req.idempotencyKey(), corr);
            ControlResponse existing = new ControlResponse(corr, req.idempotencyKey(),
                watchFor("swarm-create", swarmId), timeout.toMillis());
            log.info("[CTRL] follow swarm-create swarm={} correlation={} idempotencyKey={}", swarmId, corr, req.idempotencyKey());
            response = ResponseEntity.accepted().body(existing);
            logRestResponse("POST", path, response);
            return response;
        }

        Optional<Pending> controllerPending = creates.controllerPending(swarmId);
        if (controllerPending.isPresent()) {
            Pending pending = controllerPending.get();
            idempotency.rollback(swarmId, "swarm-create", CREATE_LOCK_KEY, lockCorrelation);
            idempotency.reserve(swarmId, "swarm-create", req.idempotencyKey(), pending.correlationId());
            ControlResponse existing = new ControlResponse(pending.correlationId(), req.idempotencyKey(),
                watchFor("swarm-create", swarmId), timeout.toMillis());
            log.info("[CTRL] follow swarm-create swarm={} correlation={} idempotencyKey={}", swarmId, pending.correlationId(), req.idempotencyKey());
            response = ResponseEntity.accepted().body(existing);
            logRestResponse("POST", path, response);
            return response;
        }

        try {
            response = idempotentSend("swarm-create", swarmId, req.idempotencyKey(), timeout.toMillis(), lockCorrelation, corr -> {
                String templateId = req.templateId();
                ScenarioPlan planDescriptor = fetchScenario(templateId);
                SwarmTemplate template = planDescriptor.template();
                ScenarioPlan.Plan timeline = planDescriptor.plan();
                if (timeline == null) {
                    timeline = new ScenarioPlan.Plan(List.of(), List.of());
                }
                String image = requireImage(template, templateId);
                SwarmPlan originalPlan = planDescriptor.toSwarmPlan(swarmId);
                prepareScenarioRuntime(templateId, swarmId);
                String runtimeRootSource = scenariosRuntimeRootSource;
                if (runtimeRootSource == null || runtimeRootSource.isBlank()) {
                    throw new IllegalStateException("POCKETHIVE_SCENARIOS_RUNTIME_ROOT must not be blank");
                }
                String scenarioVolume = runtimeRootSource + "/" + swarmId + ":/app/scenario:ro";
                String sutId = normalize(req.sutId());
                io.pockethive.swarm.model.SutEnvironment sutEnvironment = null;
                if (sutId != null) {
                    try {
                        sutEnvironment = scenarios.fetchSutEnvironment(sutId);
                    } catch (Exception ex) {
                        throw new IllegalStateException(
                            "Failed to resolve SUT environment '%s'".formatted(sutId), ex);
                    }
                }
                final io.pockethive.swarm.model.SutEnvironment finalSutEnvironment = sutEnvironment;
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
                            if (finalSutEnvironment != null && config != null && !config.isEmpty()) {
                                config = applySutConfigTemplates(config, finalSutEnvironment);
                            }
                            return new io.pockethive.swarm.model.Bee(
                                bee.id(),
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
                String instanceId = BeeNameGenerator.generate("swarm-controller", swarmId);
                plans.register(instanceId, plan);
                try {
                    String planJson = json.writeValueAsString(timeline);
                    timelines.register(instanceId, planJson);
                } catch (JsonProcessingException e) {
                    throw new IllegalStateException("Failed to serialize scenario plan for swarm " + swarmId, e);
                }
                boolean autoPull = Boolean.TRUE.equals(req.autoPullImages());
                Swarm swarm = lifecycle.startSwarm(
                    swarmId,
                    image,
                    instanceId,
                    new SwarmTemplateMetadata(templateId, image, plan.bees()),
                    autoPull);
                try {
                    var data = new LinkedHashMap<String, Object>();
                    data.put("templateId", req.templateId());
                    data.put("sutId", sutId);
                    data.put("autoPullImages", autoPull);
                    data.put("controllerInstance", instanceId);
                    data.put("runId", swarm.getRunId());
                    hiveJournal.append(HiveJournalEntry.info(
                        swarmId,
                        HiveJournal.Direction.LOCAL,
                        "command",
                        "swarm-create",
                        "orchestrator",
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
                if (autoPull) {
                    lifecycle.preloadSwarmImages(swarmId);
                }
                creates.register(swarm.getInstanceId(), new Pending(
                    swarmId,
                    swarm.getInstanceId(),
                    corr,
                    req.idempotencyKey(),
                    Phase.CONTROLLER,
                    Instant.now().plus(timeout)));
            });
        } finally {
            idempotency.rollback(swarmId, "swarm-create", CREATE_LOCK_KEY, lockCorrelation);
        }
        logRestResponse("POST", path, response);
        return response;
    }

    /**
     * POST {@code /api/swarms/{swarmId}/start} — enable work inside an existing swarm controller.
     * <p>
     * The body accepts {@link ControlRequest}, typically {@code {"idempotencyKey":"cli-42"}}. We invoke
     * {@link #sendSignal(String, String, String, long)} with the {@code swarm-start} signal so the swarm
     * controller replays its {@code config-update(enabled=true)} flow. The response echoes the correlation
     * id and watch keys (e.g. {@code event.outcome.swarm-start.<swarmId>.swarm-controller.<instance>}) for observability.
     */
    @PostMapping("/{swarmId}/start")
    public ResponseEntity<ControlResponse> start(@PathVariable String swarmId, @RequestBody ControlRequest req) {
        String path = "/api/swarms/" + swarmId + "/start";
        logRestRequest("POST", path, req);
        ResponseEntity<ControlResponse> response = sendSignal("swarm-start", swarmId, req.idempotencyKey(), 180_000L);
        logRestResponse("POST", path, response);
        return response;
    }

    /**
     * POST {@code /api/swarms/{swarmId}/stop} — pause workload processing.
     * <p>
     * Sends a {@code swarm-stop} control signal that causes the swarm controller to publish
     * {@code config-update(enabled=false)} and eventually emit {@code ready.swarm-stop}. Clients can reuse
     * the idempotency key when retrying to guarantee the same correlation id.
     */
    @PostMapping("/{swarmId}/stop")
    public ResponseEntity<ControlResponse> stop(@PathVariable String swarmId, @RequestBody ControlRequest req) {
        String path = "/api/swarms/" + swarmId + "/stop";
        logRestRequest("POST", path, req);
        ResponseEntity<ControlResponse> response = sendSignal("swarm-stop", swarmId, req.idempotencyKey(), 90_000L);
        logRestResponse("POST", path, response);
        return response;
    }

    /**
     * POST {@code /api/swarms/{swarmId}/remove} — delete queues and terminate the swarm controller.
     * <p>
     * After forwarding a {@code swarm-remove} signal the orchestrator expects a
     * {@code ready.swarm-remove} event. The helper clarifies correlation usage so runbooks can wait for
     * completion before cleaning up UI state.
     */
    @PostMapping("/{swarmId}/remove")
    public ResponseEntity<ControlResponse> remove(@PathVariable String swarmId, @RequestBody ControlRequest req) {
        String path = "/api/swarms/" + swarmId + "/remove";
        logRestRequest("POST", path, req);
        ResponseEntity<ControlResponse> response = sendSignal("swarm-remove", swarmId, req.idempotencyKey(), 180_000L);
        logRestResponse("POST", path, response);
        return response;
    }

    /**
     * Wrap common send logic for lifecycle signals ({@code swarm-start|stop|remove}).
     * <p>
     * We build a {@link ControlSignal} scoped to the swarm controller instance, resolve the routing key via
     * {@link ControlPlaneRouting#signal(String, String, String, String)}, and update the
     * {@link SwarmRegistry}/{@link SwarmCreateTracker} so downstream watchers know which confirmations to
     * expect. The timeout is expressed in milliseconds for convenient alignment with API docs.
     */
	    private ResponseEntity<ControlResponse> sendSignal(String signal, String swarmId, String idempotencyKey, long timeoutMs) {
	        Duration timeout = Duration.ofMillis(timeoutMs);
	        return idempotentSend(signal, swarmId, idempotencyKey, timeoutMs, corr -> {
	            String controllerInstance = registry.find(swarmId)
	                .map(Swarm::getInstanceId)
	                .orElse(null);
	            if (controllerInstance == null || controllerInstance.isBlank()) {
	                throw new IllegalStateException("Swarm " + swarmId + " is not registered with a controller instance");
	            }
	            ControlScope target = ControlScope.forInstance(swarmId, "swarm-controller", controllerInstance);
	            ControlSignal payload = switch (signal) {
	                case "swarm-start" -> ControlSignals.swarmStart(originInstanceId, target, corr, idempotencyKey);
	                case "swarm-stop" -> ControlSignals.swarmStop(originInstanceId, target, corr, idempotencyKey);
	                case "swarm-remove" -> ControlSignals.swarmRemove(originInstanceId, target, corr, idempotencyKey);
	                default -> throw new IllegalArgumentException("Unsupported lifecycle signal: " + signal);
	            };
	            String jsonPayload = toJson(payload);
	            String routingKey = ControlPlaneRouting.signal(signal, swarmId, "swarm-controller", controllerInstance);
	            sendControl(routingKey, jsonPayload, signal);
                try {
                    var data = new LinkedHashMap<String, Object>();
                    data.put("controllerInstance", controllerInstance);
                    data.put("timeoutMs", timeoutMs);
                    hiveJournal.append(HiveJournalEntry.info(
                        swarmId,
                        HiveJournal.Direction.OUT,
                        "signal",
                        signal,
                        "orchestrator",
                        target,
                        corr,
                        idempotencyKey,
                        routingKey,
                        data,
                        null,
                        null));
                } catch (Exception ignore) {
                    // best-effort
                }
	            if ("swarm-start".equals(signal)) {
	                registry.markStartIssued(swarmId);
                creates.expectStart(swarmId, corr, idempotencyKey, timeout);
            } else if ("swarm-stop".equals(signal)) {
                creates.expectStop(swarmId, corr, idempotencyKey, timeout);
            }
        });
    }

    /**
     * Apply the orchestrator's idempotency contract to an outgoing signal.
     * <p>
     * If the {@link IdempotencyStore} already contains a correlation for {@code (swarmId, signal, key)} we
     * short-circuit and return the existing {@link ControlResponse}. Otherwise a new correlation id is
     * generated, {@code action.accept(corr)} publishes the control message, and the idempotency record is
     * stored so retries remain consistent.
     */
    private ResponseEntity<ControlResponse> idempotentSend(String signal, String swarmId, String idempotencyKey,
                                                           long timeoutMs, java.util.function.Consumer<String> action) {
        return idempotentSend(signal, swarmId, idempotencyKey, timeoutMs, UUID.randomUUID().toString(), action);
    }

    private ResponseEntity<ControlResponse> idempotentSend(String signal, String swarmId, String idempotencyKey,
                                                           long timeoutMs, String correlation,
                                                           java.util.function.Consumer<String> action) {
        Optional<String> existing = idempotency.reserve(swarmId, signal, idempotencyKey, correlation);
        if (existing.isPresent() && !existing.get().equals(correlation)) {
            String corr = existing.get();
            ControlResponse resp = new ControlResponse(corr, idempotencyKey,
                watchFor(signal, swarmId), timeoutMs);
            log.info("[CTRL] reuse signal={} swarm={} correlation={} idempotencyKey={}", signal, swarmId, corr, idempotencyKey);
            return ResponseEntity.accepted().body(resp);
        }

        try {
            action.accept(correlation);
        } catch (RuntimeException e) {
            idempotency.rollback(swarmId, signal, idempotencyKey, correlation);
            throw e;
        }

        ControlResponse resp = new ControlResponse(correlation, idempotencyKey,
            watchFor(signal, swarmId), timeoutMs);
        log.info("[CTRL] issue signal={} swarm={} correlation={} idempotencyKey={} timeoutMs={}",
            signal, swarmId, correlation, idempotencyKey, timeoutMs);
        return ResponseEntity.accepted().body(resp);
    }

    /**
     * Resolve the AMQP routing keys a client should watch for completion/error signals.
     * <p>
     * For {@code swarm-create} we watch the orchestrator's own events; subsequent lifecycle actions watch
     * the specific swarm-controller instance (e.g. {@code event.outcome.swarm-start.<swarmId>.swarm-controller.<instance>}).
     * These values are documented in {@code docs/ORCHESTRATOR-REST.md#control-response}.
     */
    private ControlResponse.Watch watchFor(String signal, String swarmId) {
        if ("swarm-create".equals(signal)) {
            ConfirmationScope scope = new ConfirmationScope(swarmId, "orchestrator", originInstanceId);
            return new ControlResponse.Watch(
                ControlPlaneRouting.event("outcome", "swarm-create", scope),
                ControlPlaneRouting.event("alert", "alert", scope));
        }
        String controllerInstance = registry.find(swarmId)
            .map(Swarm::getInstanceId)
            .orElse(null);
        if (controllerInstance == null || controllerInstance.isBlank()) {
            throw new IllegalStateException("Swarm " + swarmId + " is not registered with a controller instance");
        }
        ConfirmationScope scope = new ConfirmationScope(swarmId, "swarm-controller", controllerInstance);
        return new ControlResponse.Watch(
            ControlPlaneRouting.event("outcome", signal, scope),
            ControlPlaneRouting.event("alert", "alert", scope));
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

    private String prepareScenarioRuntime(String templateId, String swarmId) {
        try {
            String runtimeDir = scenarios.prepareScenarioRuntime(templateId, swarmId);
            if (runtimeDir == null || runtimeDir.isBlank()) {
                throw new IllegalStateException(
                    "Scenario runtime for template %s and swarm %s returned empty path".formatted(templateId, swarmId));
            }
            return runtimeDir;
        } catch (Exception e) {
            log.warn("failed to prepare scenario runtime for template {} and swarm {}", templateId, swarmId, e);
            throw new IllegalStateException(
                "Failed to prepare scenario runtime for template %s".formatted(templateId), e);
        }
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

    public record ControlRequest(String idempotencyKey, String notes) {}

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
    public ResponseEntity<List<SwarmSummary>> list() {
        String path = "/api/swarms";
        logRestRequest("GET", path, null);
        List<SwarmSummary> payload = registry.all().stream()
            .sorted(Comparator.comparing(Swarm::getId))
            .map(swarm -> toSummaryFromStatusFull(swarm, swarm.getControllerStatusFull()))
            .filter(Objects::nonNull)
            .toList();
        ResponseEntity<List<SwarmSummary>> response = ResponseEntity.ok(payload);
        logRestResponse("GET", path, response);
        return response;
    }

    /**
     * GET {@code /api/swarms/{swarmId}} — fetch the latest cached swarm-controller status-full snapshot.
     */
    @GetMapping("/{swarmId}")
    public ResponseEntity<StatusFullSnapshot> view(@PathVariable String swarmId) {
        String path = "/api/swarms/" + swarmId;
        logRestRequest("GET", path, null);
        ResponseEntity<StatusFullSnapshot> response;
        Optional<Swarm> swarmOpt = registry.find(swarmId);
        if (swarmOpt.isEmpty()) {
            response = ResponseEntity.notFound().build();
        } else {
            Swarm swarm = swarmOpt.get();
            JsonNode statusFull = swarm.getControllerStatusFull();
            if (statusFull == null) {
                response = ResponseEntity.notFound().build();
            } else {
                StatusFullSnapshot snapshot = new StatusFullSnapshot(
                    swarm.getControllerStatusReceivedAt(),
                    STATUS_FULL_STALE_AFTER.getSeconds(),
                    statusFull);
                response = ResponseEntity.ok(snapshot);
            }
        }
        logRestResponse("GET", path, response);
        return response;
    }

    private SwarmSummary toSummaryFromStatusFull(Swarm swarm, JsonNode statusFull) {
        if (statusFull == null || statusFull.isMissingNode()) {
            return null;
        }
        JsonNode scope = statusFull.path("scope");
        JsonNode data = statusFull.path("data");
        JsonNode context = data.path("context");
        JsonNode workers = context.path("workers");

        String id = swarm.getId();

        SwarmStatus status = parseSwarmStatus(textOrNull(context, "swarmStatus"), null);
        SwarmHealth health = parseSwarmHealth(textOrNull(context, "swarmHealth"), null);
        Instant heartbeat = parseInstant(textOrNull(statusFull, "timestamp"));

        JsonNode enabledNode = data.get("enabled");
        if (enabledNode == null || !enabledNode.isBoolean()) {
            return null;
        }
        boolean workEnabled = enabledNode.asBoolean();

        String templateId = textOrNull(data.path("runtime"), "templateId");
        String controllerImage = textOrNull(data.path("runtime"), "image");
        String sutId = textOrNull(context, "sutId");
        List<BeeSummary> bees = beesFromWorkers(workers);

        String stackName = textOrNull(data.path("runtime"), "stackName");
        return new SwarmSummary(
            id,
            status,
            health,
            heartbeat,
            workEnabled,
            templateId,
            controllerImage,
            sutId,
            stackName,
            bees);
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

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception ex) {
            return null;
        }
    }

    private static SwarmStatus parseSwarmStatus(String value, SwarmStatus fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return SwarmStatus.valueOf(value.trim().toUpperCase());
        } catch (Exception ex) {
            return fallback;
        }
    }

    private static SwarmHealth parseSwarmHealth(String value, SwarmHealth fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.trim().toUpperCase();
        return switch (normalized) {
            case "RUNNING" -> SwarmHealth.RUNNING;
            case "FAILED" -> SwarmHealth.FAILED;
            case "DEGRADED" -> SwarmHealth.DEGRADED;
            default -> fallback;
        };
    }

    private static List<BeeSummary> beesFromWorkers(JsonNode workersNode) {
        if (workersNode == null || workersNode.isMissingNode() || !workersNode.isArray()) {
            return List.of();
        }
        Set<String> roles = new LinkedHashSet<>();
        for (JsonNode workerNode : workersNode) {
            String role = textOrNull(workerNode, "role");
            if (role != null) {
                roles.add(role);
            }
        }
        if (roles.isEmpty()) {
            return List.of();
        }
        List<BeeSummary> bees = new ArrayList<>(roles.size());
        for (String role : roles) {
            bees.add(new BeeSummary(role, null));
        }
        return bees;
    }

    public record SwarmSummary(String id,
                               SwarmStatus status,
                               SwarmHealth health,
                               java.time.Instant heartbeat,
                               boolean workEnabled,
                               String templateId,
                               String controllerImage,
                               String sutId,
                               String stackName,
                               List<BeeSummary> bees) {
        public SwarmSummary {
            bees = bees == null ? List.of() : List.copyOf(bees);
        }
    }

    public record BeeSummary(String role, String image) {}

    public record StatusFullSnapshot(java.time.Instant receivedAt,
                                     long staleAfterSec,
                                     JsonNode envelope) {
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
