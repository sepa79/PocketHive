package io.pockethive.orchestrator.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlSignal;
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
import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.SwarmPlan;
import io.pockethive.swarm.model.SwarmTemplate;
import io.pockethive.controlplane.spring.ControlPlaneProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Objects;

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
    public ResponseEntity<ControlResponse> create(@PathVariable String swarmId, @RequestBody SwarmCreateRequest req) {
        String path = "/api/swarms/" + swarmId + "/create";
        logRestRequest("POST", path, req);
        Duration timeout = Duration.ofMillis(120_000L);
        ResponseEntity<ControlResponse> response = idempotentSend("swarm-create", swarmId, req.idempotencyKey(), timeout.toMillis(), corr -> {
            String templateId = req.templateId();
            SwarmTemplate template = fetchTemplate(templateId);
            String image = requireImage(template, templateId);
            List<Bee> bees = template.bees();
            String instanceId = BeeNameGenerator.generate("swarm-controller", swarmId);
            plans.register(instanceId, new SwarmPlan(swarmId, bees));
            Swarm swarm = lifecycle.startSwarm(swarmId, image, instanceId);
            creates.register(swarm.getInstanceId(), new Pending(
                swarmId,
                swarm.getInstanceId(),
                corr,
                req.idempotencyKey(),
                Phase.CONTROLLER,
                Instant.now().plus(timeout)));
        });
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
        return idempotency.findCorrelation(swarmId, signal, idempotencyKey)
            .map(corr -> {
                ControlResponse resp = new ControlResponse(corr, idempotencyKey,
                    watchFor(signal, swarmId), timeoutMs);
                log.info("[CTRL] reuse signal={} swarm={} correlation={} idempotencyKey={}", signal, swarmId, corr, idempotencyKey);
                return ResponseEntity.accepted().body(resp);
            })
            .orElseGet(() -> {
                String corr = UUID.randomUUID().toString();
                action.accept(corr);
                idempotency.record(swarmId, signal, idempotencyKey, corr);
                ControlResponse resp = new ControlResponse(corr, idempotencyKey,
                    watchFor(signal, swarmId), timeoutMs);
                log.info("[CTRL] issue signal={} swarm={} correlation={} idempotencyKey={} timeoutMs={}",
                    signal, swarmId, corr, idempotencyKey, timeoutMs);
                return ResponseEntity.accepted().body(resp);
            });
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
    private SwarmTemplate fetchTemplate(String templateId) {
        try {
            SwarmTemplate template = scenarios.fetchTemplate(templateId);
            if (template == null) {
                throw new IllegalStateException("Template %s was not found".formatted(templateId));
            }
            return template;
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

    /**
     * GET {@code /api/swarms/{swarmId}} — fetch a snapshot of swarm state for dashboards.
     * <p>
     * Returns {@link SwarmView} with status, health, heartbeat, and toggle booleans. Example response:
     * <pre>{@code
     * {
     *   "id": "demo",
     *   "status": "RUNNING",
     *   "health": "HEALTHY",
     *   "heartbeat": "2024-03-15T12:00:00Z",
     *   "workEnabled": true,
     *   "controllerEnabled": true
     * }
     * }</pre>
     */
    @GetMapping("/{swarmId}")
    public ResponseEntity<SwarmView> view(@PathVariable String swarmId) {
        String path = "/api/swarms/" + swarmId;
        logRestRequest("GET", path, null);
        ResponseEntity<SwarmView> response = registry.find(swarmId)
            .map(s -> ResponseEntity.ok(new SwarmView(
                s.getId(),
                s.getStatus(),
                s.getHealth(),
                s.getHeartbeat(),
                s.isWorkEnabled(),
                s.isControllerEnabled())))
            .orElse(ResponseEntity.notFound().build());
        logRestResponse("GET", path, response);
        return response;
    }

    public record SwarmView(String id,
                             SwarmStatus status,
                             SwarmHealth health,
                             java.time.Instant heartbeat,
                             boolean workEnabled,
                             boolean controllerEnabled) {}

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
