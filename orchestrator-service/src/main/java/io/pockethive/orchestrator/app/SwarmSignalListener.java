package io.pockethive.orchestrator.app;

import io.pockethive.Topology;
import io.pockethive.observability.StatusEnvelopeBuilder;
import io.pockethive.observability.ObservabilityContextUtil;
import io.pockethive.orchestrator.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
public class SwarmSignalListener {
    private static final Logger log = LoggerFactory.getLogger(SwarmSignalListener.class);
    private final ContainerLifecycleManager lifecycleManager;
    private final AmqpTemplate rabbit;
    private final String instanceId;
    private final SwarmTemplate template;

    public SwarmSignalListener(ContainerLifecycleManager lifecycleManager, AmqpTemplate rabbit,
                               String instanceId, SwarmTemplate template) {
        this.lifecycleManager = lifecycleManager;
        this.rabbit = rabbit;
        this.instanceId = instanceId;
        this.template = template;
    }

    @RabbitListener(queues = "#{controlQueue.name}")
    public void handle(String body, @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey) {
        if (routingKey == null) {
            return;
        }

        if (routingKey.startsWith("sig.swarm-create.")) {
            String swarmId = routingKey.substring("sig.swarm-create.".length());
            try {
                Swarm swarm = lifecycleManager.startSwarm(swarmId, body);
                var ctx = ObservabilityContextUtil.init("orchestrator", instanceId, swarmId);
                String payload = new StatusEnvelopeBuilder()
                        .kind("status-full")
                        .role("orchestrator")
                        .instance(instanceId)
                        .swarmId(swarm.getId())
                        .toJson();
                Message msg = MessageBuilder.withBody(payload.getBytes())
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .setHeader(ObservabilityContextUtil.HEADER, ObservabilityContextUtil.toHeader(ctx))
                        .build();
                rabbit.convertAndSend(Topology.CONTROL_EXCHANGE,
                        "ev.status-full.orchestrator." + instanceId, msg);
            } catch (Exception e) {
                log.error("Failed to start swarm {}", swarmId, e);
            }
        } else if (routingKey.startsWith("ev.ready.herald.")) {
            SwarmPlan plan = new SwarmPlan(Topology.SWARM_ID, template);
            rabbit.convertAndSend(Topology.CONTROL_EXCHANGE,
                    "sig.swarm-start." + Topology.SWARM_ID, plan);
        }
    }
}
