package io.pockethive.swarmcontroller;

import io.pockethive.Topology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Component;

/**
 * Emits a readiness event once the application is ready to receive signals.
 */
@Component
public class ReadyEmitter {

    private static final Logger log = LoggerFactory.getLogger(ReadyEmitter.class);

    private final RabbitTemplate rabbit;
    private final String instanceId;

    public ReadyEmitter(RabbitTemplate rabbit, @Qualifier("instanceId") String instanceId) {
        this.rabbit = rabbit;
        this.instanceId = instanceId;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void emit() {
        String rk = "ev.ready.swarm-controller." + instanceId;
        log.info("[CTRL] SEND rk={} inst={} payload={}", rk, instanceId, "");
        rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, rk, "");
    }
}
