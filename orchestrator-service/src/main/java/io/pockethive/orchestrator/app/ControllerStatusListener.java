package io.pockethive.orchestrator.app;

import io.pockethive.orchestrator.domain.HiveJournal;
import io.pockethive.control.ControlScope;
import io.pockethive.control.StatusMetric;
import io.pockethive.controlplane.codec.ControlPlaneCodec;
import io.pockethive.controlplane.ControlPlaneEventTypes;
import io.pockethive.controlplane.ControlPlaneRoles;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.routing.ControlPlaneRouting.RoutingKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Responsibility: Decode controller status events and delegate catalogue admission/observation.
 * Must not: Own lifecycle operations, construct terminal outcomes, or publish Orchestrator status.
 * Contract: RESP-ORCHESTRATOR-INGRESS — docs/architecture/runtime-responsibilities.md#resp-orchestrator-ingress.
 * ControllerStatusService owns discovery and identity conflict reporting.
 */
@Component
@EnableScheduling
public class ControllerStatusListener {
    private static final Logger log = LoggerFactory.getLogger(ControllerStatusListener.class);

    private final ControlPlaneCodec codec;
    private final ControllerStatusService statuses;
    private final HiveJournal hiveJournal;
    private final ControlPlaneJournalErrors journalErrors;

    public ControllerStatusListener(ControlPlaneCodec codec,
                                    ControllerStatusService statuses, HiveJournal hiveJournal) {
        this.codec = Objects.requireNonNull(codec, "codec");
        this.statuses = Objects.requireNonNull(statuses, "statuses");
        this.hiveJournal = Objects.requireNonNull(hiveJournal, "hiveJournal");
        this.journalErrors = new ControlPlaneJournalErrors(
            this.hiveJournal, ControlPlaneRoles.ORCHESTRATOR, "controller-status-listener");
    }

    public void handle(String body, String routingKey) {
        // Controller status messages are control-plane traffic: never requeue on failures (avoid storms).
        try {
            if (routingKey == null || routingKey.isBlank()) {
                log.error("Received controller status message with null or blank routing key; payload snippet={}", snippet(body));
                journalParseError("hive", routingKey, "missing routing key", body, null);
                return;
            }
            if (body == null || body.isBlank()) {
                log.error("Received controller status message with null or blank payload for routing key {}", routingKey);
                journalParseError("hive", routingKey, "missing payload", body, null);
                return;
            }
            String payloadSnippet = snippet(body);
            RoutingKey eventKey = ControlPlaneRouting.parseEvent(routingKey);
            boolean statusFull = eventKey != null
                && ControlPlaneEventTypes.METRIC_STATUS_FULL.equals(eventKey.type());
            boolean statusDelta = eventKey != null
                && ControlPlaneEventTypes.METRIC_STATUS_DELTA.equals(eventKey.type());
            if (statusFull || statusDelta) {
                log.debug("[CTRL] RECV rk={} payload={}", routingKey, payloadSnippet);
            } else {
                log.info("[CTRL] RECV rk={} payload={}", routingKey, payloadSnippet);
            }
            StatusMetric status = codec.decode(body, routingKey, StatusMetric.class);
            if (statusFull || statusDelta) {
                statuses.accept(status, statusFull, routingKey);
            }
        } catch (Exception e) {
            log.error("Ignoring controller status message due to handler exception; rk={} payload snippet={}", routingKey, snippet(body), e);
            journalParseError(bestEffortSwarmIdFromRouting(routingKey), routingKey, "handler exception", body, e);
        }
    }

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

    private void journalParseError(String swarmId,
                                  String routingKey,
                                  String reason,
                                  String body,
                                  Exception exception) {
        String resolvedSwarmId = swarmId != null && !swarmId.isBlank() ? swarmId : "hive";
        journalErrors.errorDrop(
            resolvedSwarmId,
            HiveJournal.Direction.IN,
            "status-parse-error",
            new ControlScope(resolvedSwarmId, "orchestrator", "controller-status-listener"),
            routingKey,
            reason,
            body,
            exception);
    }

    private static String bestEffortSwarmIdFromRouting(String routingKey) {
        var parsed = io.pockethive.controlplane.routing.ControlPlaneRouting.parseEvent(routingKey);
        return parsed != null && parsed.swarmId() != null && !parsed.swarmId().isBlank()
            ? parsed.swarmId()
            : "hive";
    }

}
