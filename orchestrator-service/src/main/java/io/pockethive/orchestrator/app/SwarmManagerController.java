package io.pockethive.orchestrator.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.CommandTarget;
import io.pockethive.control.ControlSignal;
import io.pockethive.control.ConfirmationScope;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.orchestrator.domain.IdempotencyStore;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST surface for toggling swarm controller workloads on or off via {@code /api/swarm-managers}.
 * <p>
 * While {@link SwarmController} handles full lifecycle operations, this controller focuses on
 * {@code config-update} fan-out so operators can pause or resume work across one or many swarms. Each
 * endpoint below references {@code docs/ORCHESTRATOR-REST.md#swarm-manager} and includes concrete JSON
 * examples for junior engineers testing the API with {@code curl}.
 */
@RestController
@RequestMapping("/api/swarm-managers")
public class SwarmManagerController {
    private static final Logger log = LoggerFactory.getLogger(SwarmManagerController.class);
    private static final long CONFIG_UPDATE_TIMEOUT_MS = 60_000L;

    private final SwarmRegistry registry;
    private final AmqpTemplate rabbit;
    private final IdempotencyStore idempotency;
    private final ObjectMapper json;
    private final String controlExchange;

    public SwarmManagerController(SwarmRegistry registry,
                                  AmqpTemplate rabbit,
                                  IdempotencyStore idempotency,
                                  ObjectMapper json,
                                  @Qualifier("controlPlaneExchange") TopicExchange controlExchange) {
        this.registry = registry;
        this.rabbit = rabbit;
        this.idempotency = idempotency;
        this.json = json;
        this.controlExchange = Objects.requireNonNull(controlExchange, "controlExchange").getName();
    }

    /**
     * POST {@code /api/swarm-managers/enabled} — fan-out a toggle to every registered swarm.
     * <p>
     * Accepts {@link ToggleRequest}; a typical request looks like:
     * <pre>{@code
     * {
     *   "idempotencyKey": "ops-555",
     *   "enabled": false,
     *   "notes": "pause all swarms",
     *   "commandTarget": {"scope": "SWARM"}
     * }
     * }</pre>
     * The response body describes which swarms received new signals versus which reused existing
     * correlations so dashboards know whether to expect {@code ready.config-update} events.
     */
    @PostMapping("/enabled")
    public ResponseEntity<FanoutControlResponse> updateAll(@RequestBody ToggleRequest request) {
        String path = "/api/swarm-managers/enabled";
        logRestRequest("POST", path, request);
        FanoutControlResponse body = dispatch(registry.all(), request);
        ResponseEntity<FanoutControlResponse> response = ResponseEntity.accepted().body(body);
        logRestResponse("POST", path, response);
        return response;
    }

    /**
     * POST {@code /api/swarm-managers/{swarmId}/enabled} — target a single swarm controller.
     * <p>
     * Behaves like {@link #updateAll(ToggleRequest)} but restricts the fan-out to the provided
     * {@code swarmId}. Returns 404 if the swarm has not yet registered a controller instance, guiding
     * API clients to call the create/start workflow first.
     */
    @PostMapping("/{swarmId}/enabled")
    public ResponseEntity<FanoutControlResponse> updateOne(@PathVariable String swarmId,
                                                           @RequestBody ToggleRequest request) {
        String path = "/api/swarm-managers/" + swarmId + "/enabled";
        logRestRequest("POST", path, request);
        ResponseEntity<FanoutControlResponse> response = registry.find(swarmId)
            .map(swarm -> ResponseEntity.accepted().body(dispatch(List.of(swarm), request)))
            .orElseGet(() -> ResponseEntity.notFound().build());
        logRestResponse("POST", path, response);
        return response;
    }

    /**
     * Iterate through the provided swarms, publishing idempotent {@code config-update} signals.
     * <p>
     * For each swarm we check {@link IdempotencyStore}; if a correlation exists we return it immediately.
     * Otherwise we construct {@link ControlSignal#forInstance(String, String, String, String, String, String, CommandTarget, Map)}
     * with routing keys derived from {@link #routingKey(String, String)} and record the new correlation so
     * retries remain deterministic. The resulting {@link FanoutControlResponse} lists each dispatch with a
     * {@code reused} flag for observability.
     */
    private FanoutControlResponse dispatch(Iterable<Swarm> swarms, ToggleRequest request) {
        List<Dispatch> dispatches = new ArrayList<>();
        for (Swarm swarm : swarms) {
            if (swarm == null || swarm.getInstanceId() == null || swarm.getInstanceId().isBlank()) {
                continue;
            }
            String swarmId = swarm.getId();
            String swarmSegment = segmentOrAll(swarmId);
            String scope = swarmId;
            idempotency.findCorrelation(scope, ControlPlaneSignals.CONFIG_UPDATE, request.idempotencyKey())
                .ifPresentOrElse(correlation -> dispatches.add(new Dispatch(swarmSegment, swarm.getInstanceId(),
                        accepted(correlation, request.idempotencyKey(), swarmSegment, swarm.getInstanceId()), true)),
                    () -> {
                        String correlation = UUID.randomUUID().toString();
                        ControlSignal payload = ControlSignal.forInstance(
                            ControlPlaneSignals.CONFIG_UPDATE,
                            swarmId,
                            "swarm-controller",
                            swarm.getInstanceId(),
                            correlation,
                            request.idempotencyKey(),
                            request.commandTarget(),
                            argsFor(request));
                        sendControl(routingKey(swarmSegment, swarm.getInstanceId()), toJson(payload), request.commandTarget());
                        idempotency.record(scope, ControlPlaneSignals.CONFIG_UPDATE, request.idempotencyKey(), correlation);
                        dispatches.add(new Dispatch(swarmSegment, swarm.getInstanceId(),
                            accepted(correlation, request.idempotencyKey(), swarmSegment, swarm.getInstanceId()), false));
                    });
        }
        return new FanoutControlResponse(dispatches);
    }

    /**
     * Compute the control-plane routing key for a given swarm segment and controller instance.
     * <p>
     * Example: {@code routingKey("demo", "swarm-controller-demo-1")} yields
     * {@code sig.config-update.demo.swarm-controller.swarm-controller-demo-1}.
     */
    private static String routingKey(String swarmSegment, String instanceId) {
        return ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, swarmSegment, "swarm-controller", instanceId);
    }

    /**
     * Convert the toggle request into the structured {@code args} block expected by worker runtimes.
     * <p>
     * Currently we only forward the {@code enabled} flag, but this method centralises the mapping so
     * future extensions (e.g. role-specific limits) remain consistent across fan-out calls.
     */
    private Map<String, Object> argsFor(ToggleRequest request) {
        Map<String, Object> args = new LinkedHashMap<>();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", request.enabled());
        args.put("data", data);
        return args;
    }

    /**
     * Build the {@link ControlResponse} returned to clients for a successful dispatch.
     * <p>
     * The watch section uses {@link ControlPlaneRouting#event(String, ConfirmationScope)} to point to the
     * ready/error queues the UI should monitor. {@code CONFIG_UPDATE_TIMEOUT_MS} matches the contract in
     * {@code docs/ORCHESTRATOR-REST.md} so front-ends align their polling windows.
     */
    private ControlResponse accepted(String correlationId,
                                     String idempotencyKey,
                                     String swarmSegment,
                                     String instanceId) {
        ConfirmationScope scope = new ConfirmationScope(swarmSegment, "swarm-controller", instanceId);
        ControlResponse.Watch watch = new ControlResponse.Watch(
            ControlPlaneRouting.event("ready.config-update", scope),
            ControlPlaneRouting.event("error.config-update", scope)
        );
        return new ControlResponse(correlationId, idempotencyKey, watch, CONFIG_UPDATE_TIMEOUT_MS);
    }

    /**
     * Publish a control message while logging the selected {@link CommandTarget} for debugging.
     * <p>
     * Example log when targeting a single instance:
     * {@code [CTRL] SEND {"scope":"INSTANCE"} rk=sig.config-update.demo.swarm-controller.instance payload={...}}
     */
    private void sendControl(String routingKey, String payload, CommandTarget context) {
        String label = context == null ? "SEND" : "SEND " + context.json();
        log.info("[CTRL] {} rk={} payload={}", label, routingKey, snippet(payload));
        rabbit.convertAndSend(controlExchange, routingKey, payload);
    }

    /**
     * Serialize {@link ControlSignal} payloads, failing fast with {@link IllegalStateException} if
     * serialization fails so REST callers receive an actionable 500 response.
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
     * Structured logging for incoming requests (mirrors {@link SwarmController#logRestRequest}).
     */
    private void logRestRequest(String method, String path, Object body) {
        log.info("[REST] {} {} request={}", method, path, toSafeString(body));
    }

    /**
     * Structured logging for responses so operators can correlate toggles with HTTP statuses.
     */
    private void logRestResponse(String method, String path, ResponseEntity<?> response) {
        if (response == null) {
            return;
        }
        log.info("[REST] {} {} -> status={} body={}", method, path, response.getStatusCode(), toSafeString(response.getBody()));
    }

    /**
     * Clamp large JSON payloads before logging to keep entries readable.
     */
    private static String toSafeString(Object value) {
        if (value == null) {
            return "";
        }
        String text = value.toString();
        if (text.length() > 300) {
            return text.substring(0, 300) + "...";
        }
        return text;
    }

    /**
     * Trim AMQP payloads for logging while retaining the opening JSON structure.
     */
    private static String snippet(String payload) {
        if (payload == null) {
            return "";
        }
        String trimmed = payload.strip();
        if (trimmed.length() > 300) {
            return trimmed.substring(0, 300) + "...";
        }
        return trimmed;
    }

    /**
     * Request payload for toggle endpoints.
     * <p>
     * Validation occurs in the canonical constructor so API consumers get immediate feedback if they
     * forget required fields. {@code commandTarget} defaults to {@link CommandTarget#SWARM} but can be set
     * to {@code INSTANCE} when targeting a single controller.
     */
    public record ToggleRequest(String idempotencyKey,
                                 Boolean enabled,
                                 String notes,
                                 CommandTarget commandTarget) {
        public ToggleRequest {
            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                throw new IllegalArgumentException("idempotencyKey is required");
            }
            if (enabled == null) {
                throw new IllegalArgumentException("enabled flag is required");
            }
            if (commandTarget == null) {
                commandTarget = CommandTarget.SWARM;
            }
            if (commandTarget != CommandTarget.SWARM && commandTarget != CommandTarget.INSTANCE) {
                throw new IllegalArgumentException("commandTarget must be swarm or instance");
            }
        }
    }

    /**
     * Response body that lists each dispatch along with correlation metadata and whether it was reused.
     */
    public record FanoutControlResponse(List<Dispatch> dispatches) {}

    public record Dispatch(String swarm, String instanceId, ControlResponse response, boolean reused) {}

    private static String segmentOrAll(String value) {
        return (value == null || value.isBlank()) ? "ALL" : value;
    }
}
