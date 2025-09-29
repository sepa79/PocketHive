package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.Topology;
import io.pockethive.control.CommandState;
import io.pockethive.control.ConfirmationScope;
import io.pockethive.control.ReadyConfirmation;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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

    private final RabbitTemplate rabbit;
    private final String instanceId;
    private final ObjectMapper mapper;

    public ReadyEmitter(RabbitTemplate rabbit, @Qualifier("instanceId") String instanceId, ObjectMapper mapper) {
        this.rabbit = rabbit;
        this.instanceId = instanceId;
        this.mapper = mapper.findAndRegisterModules();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void emit() {
        ConfirmationScope scope = ConfirmationScope.forInstance(Topology.SWARM_ID, "swarm-controller", instanceId);
        ReadyConfirmation confirmation = new ReadyConfirmation(
            Instant.now(),
            null,
            null,
            "swarm-controller",
            scope,
            CommandState.status("Ready")
        );
        String routingKey = ControlPlaneRouting.event("ready.swarm-controller", scope);
        try {
            String payload = mapper.writeValueAsString(confirmation);
            log.info("[CTRL] SEND rk={} inst={} payload={}", routingKey, instanceId, snippet(payload));
            rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, routingKey, payload);
        } catch (JsonProcessingException e) {
            log.warn("ready emit serialization", e);
        } catch (AmqpException e) {
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
