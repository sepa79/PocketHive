package io.pockethive.orchestrator.app;

import io.pockethive.Topology;
import io.pockethive.observability.StatusEnvelopeBuilder;
import io.pockethive.observability.ObservabilityContextUtil;
import io.pockethive.orchestrator.domain.Swarm;
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
    private final ContainerLifecycleManager lifecycleManager;
    private final AmqpTemplate rabbit;
    private final String instanceId;

    public SwarmSignalListener(ContainerLifecycleManager lifecycleManager, AmqpTemplate rabbit, String instanceId) {
        this.lifecycleManager = lifecycleManager;
        this.rabbit = rabbit;
        this.instanceId = instanceId;
    }

    @RabbitListener(queues = "#{controlQueue.name}")
    public void handle(String image, @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey) {
        if (routingKey != null && routingKey.startsWith("sig.swarm-create.")) {
            String swarmId = routingKey.substring("sig.swarm-create.".length());
            Swarm swarm = lifecycleManager.startSwarm(swarmId, image);
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
        }
    }
}
