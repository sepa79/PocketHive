package io.pockethive.orchestrator.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlSignal;
import io.pockethive.orchestrator.domain.ScenarioPlan;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmCreateRequest;
import io.pockethive.orchestrator.domain.SwarmCreateTracker;
import io.pockethive.orchestrator.domain.SwarmCreateTracker.Pending;
import io.pockethive.orchestrator.domain.SwarmCreateTracker.Phase;
import io.pockethive.orchestrator.domain.IdempotencyStore;
import io.pockethive.orchestrator.domain.SwarmHealth;
import io.pockethive.orchestrator.domain.SwarmPlanRegistry;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import io.pockethive.orchestrator.domain.SwarmStatus;
import io.pockethive.orchestrator.domain.SwarmTemplateMetadata;
import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.SwarmPlan;
import io.pockethive.swarm.model.SwarmTemplate;
import io.pockethive.swarm.model.TrafficPolicy;
import io.pockethive.controlplane.spring.ControlPlaneProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import io.pockethive.util.BeeNameGenerator;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.control.ConfirmationScope;

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
    private final AmqpTemplate rabbit;
    private final ContainerLifecycleManager lifecycle;
    private final SwarmCreateTracker creates;
    private final IdempotencyStore idempotency;
    private final SwarmRegistry registry;
    private final SwarmPlanRegistry plans;
    private final ScenarioClient scenarios;
    private final ObjectMapper json;
    private final String controlExchange;
    private static final String CREATE_LOCK_KEY = "__create-lock__";

    public SwarmController(AmqpTemplate rabbit,
                           ContainerLifecycleManager lifecycle,
                           SwarmCreateTracker creates,
                           IdempotencyStore idempotency,
                           SwarmRegistry registry,
                           ObjectMapper json,
                           ScenarioClient scenarios,
                           SwarmPlanRegistry plans,
                           ControlPlaneProperties controlPlaneProperties) {
        this.rabbit = rabbit;
        this.lifecycle = lifecycle;
        this.creates = creates;
        this.idempotency = idempotency;
        this.registry = registry;
        this.json = json;
        this.scenarios = scenarios;
        this.plans = plans;
        this.controlExchange = requireExchange(controlPlaneProperties);
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
                String image = requireImage(template, templateId);
                SwarmPlan plan = planDescriptor.toSwarmPlan(swarmId);
                String instanceId = BeeNameGenerator.generate("swarm-controller", swarmId);
                plans.register(instanceId, plan);
                Swarm swarm = lifecycle.startSwarm(
                    swarmId,
                    image,
                    instanceId,
                    new SwarmTemplateMetadata(templateId, image, plan.bees()));
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
     * id and watch keys (e.g. {@code ev.ready.swarm-start.swarm-controller.instance}) for observability.
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
            ControlSignal payload = ControlSignal.forSwarm(signal, swarmId, corr, idempotencyKey);
            String jsonPayload = toJson(payload);
            String routingKey = ControlPlaneRouting.signal(signal, swarmId, "swarm-controller", "ALL");
            sendControl(routingKey, jsonPayload, signal);
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
     * the specific swarm-controller instance (e.g. {@code ev.ready.swarm-start.swarm-controller.instance}).
     * These values are documented in {@code docs/ORCHESTRATOR-REST.md#control-response}.
     */
    private ControlResponse.Watch watchFor(String signal, String swarmId) {
        if ("swarm-create".equals(signal)) {
            ConfirmationScope scope = new ConfirmationScope(swarmId, "orchestrator", "ALL");
            return new ControlResponse.Watch(
                ControlPlaneRouting.event("ready.swarm-create", scope),
                ControlPlaneRouting.event("error.swarm-create", scope));
        }
        String controllerInstance = registry.find(swarmId)
            .map(Swarm::getInstanceId)
            .orElse(null);
        if (controllerInstance == null || controllerInstance.isBlank()) {
            throw new IllegalStateException("Swarm " + swarmId + " is not registered with a controller instance");
        }
        ConfirmationScope scope = new ConfirmationScope(swarmId, "swarm-controller", controllerInstance);
        return new ControlResponse.Watch(
            ControlPlaneRouting.event("ready." + signal, scope),
            ControlPlaneRouting.event("error." + signal, scope));
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

    public record ControlRequest(String idempotencyKey, String notes) {}

    private record ErrorResponse(String message) {}

    /**
     * GET {@code /api/swarms} — list all known swarms with their launch metadata.
     * <p>
     * Returns {@link SwarmSummary} for each registered swarm so dashboards can reconnect after a UI
     * refresh. Example payload:
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
            .map(this::toSummary)
            .toList();
        ResponseEntity<List<SwarmSummary>> response = ResponseEntity.ok(payload);
        logRestResponse("GET", path, response);
        return response;
    }

    /**
     * GET {@code /api/swarms/{swarmId}} — fetch a snapshot of swarm state and launch metadata.
     */
    @GetMapping("/{swarmId}")
    public ResponseEntity<SwarmSummary> view(@PathVariable String swarmId) {
        String path = "/api/swarms/" + swarmId;
        logRestRequest("GET", path, null);
        ResponseEntity<SwarmSummary> response = registry.find(swarmId)
            .map(s -> ResponseEntity.ok(toSummary(s)))
            .orElse(ResponseEntity.notFound().build());
        logRestResponse("GET", path, response);
        return response;
    }

    private SwarmSummary toSummary(Swarm swarm) {
        List<BeeSummary> bees = swarm.bees().stream()
            .map(b -> new BeeSummary(b.role(), b.image()))
            .toList();
        return new SwarmSummary(
            swarm.getId(),
            swarm.getStatus(),
            swarm.getHealth(),
            swarm.getHeartbeat(),
            swarm.isWorkEnabled(),
            swarm.isControllerEnabled(),
            swarm.templateId().orElse(null),
            swarm.controllerImage().orElse(null),
            bees);
    }

    public record SwarmSummary(String id,
                               SwarmStatus status,
                               SwarmHealth health,
                               java.time.Instant heartbeat,
                               boolean workEnabled,
                               boolean controllerEnabled,
                               String templateId,
                               String controllerImage,
                               List<BeeSummary> bees) {
        public SwarmSummary {
            bees = bees == null ? List.of() : List.copyOf(bees);
        }
    }

    public record BeeSummary(String role, String image) {}

    /**
     * Serialize control signals for RabbitMQ publishing.
     * <p>
     * Wraps {@link ObjectMapper#writeValueAsString(Object)} and rethrows as {@link IllegalStateException}
     * so REST handlers surface a 500 if serialization fails.
     */
    private String toJson(ControlSignal signal) {
        try {
            return json.writeValueAsString(signal);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize control signal %s for swarm %s".formatted(
                signal.signal(), signal.swarmId()), e);
        }
    }

    /**
     * Emit a control-plane message and log the human-readable context.
     * <p>
     * Example log line: {@code [CTRL] SEND swarm-start rk=sig.swarm-start.demo payload={...}}. Having the
     * snippet in logs helps engineers correlate REST calls to RabbitMQ traffic when debugging.
     */
    private void sendControl(String routingKey, String payload, String context) {
        String label = (context == null || context.isBlank()) ? "SEND" : "SEND " + context;
        log.info("[CTRL] {} rk={} payload={}", label, routingKey, snippet(payload));
        rabbit.convertAndSend(controlExchange, routingKey, payload);
    }

    private static String requireExchange(ControlPlaneProperties properties) {
        Objects.requireNonNull(properties, "properties");
        String exchange = properties.getExchange();
        if (exchange == null || exchange.isBlank()) {
            throw new IllegalStateException("pockethive.control-plane.exchange must not be null or blank");
        }
        return exchange;
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
}
