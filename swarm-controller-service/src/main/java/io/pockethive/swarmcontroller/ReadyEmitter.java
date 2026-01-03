package io.pockethive.swarmcontroller;

import io.pockethive.control.CommandOutcome;
import io.pockethive.control.ConfirmationScope;
import io.pockethive.control.ControlScope;
import io.pockethive.controlplane.messaging.CommandOutcomes;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.messaging.EventMessage;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.observability.ControlPlaneJson;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Emits a readiness event once the application is ready to receive signals.
 */
@Component
public class ReadyEmitter {

    private static final Logger log = LoggerFactory.getLogger(ReadyEmitter.class);

    private final ControlPlanePublisher publisher;
    private final String instanceId;
    private final SwarmControllerProperties properties;

    public ReadyEmitter(ControlPlanePublisher publisher,
                        @Qualifier("instanceId") String instanceId,
                        SwarmControllerProperties properties) {
        this.publisher = publisher;
        this.instanceId = instanceId;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void emit() {
        String swarmId = properties.getSwarmId();
        String role = properties.getRole();
        ConfirmationScope scope = ConfirmationScope.forInstance(swarmId, role, instanceId);
        CommandOutcome outcome = CommandOutcomes.success(
            role,
            instanceId,
            new ControlScope(swarmId, role, instanceId),
            null,
            null,
            "Ready",
            null,
            null,
            Instant.now()
        );
        String routingKey = ControlPlaneRouting.event("outcome", role, scope);
        try {
            String payload = ControlPlaneJson.write(outcome, "swarm-controller-ready");
            log.info("[CTRL] SEND rk={} inst={} payload={}", routingKey, instanceId, snippet(payload));
            publisher.publishEvent(new EventMessage(routingKey, payload));
        } catch (RuntimeException e) {
            log.warn("ready emit", e);
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
}
