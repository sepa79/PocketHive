package io.pockethive.orchestrator.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.orchestrator.domain.SwarmHealth;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Consumes swarm-controller aggregate status events and updates the local registry.
 */
@Component
@EnableScheduling
public class ControllerStatusListener {
    private static final Logger log = LoggerFactory.getLogger(ControllerStatusListener.class);
    private static final Duration DEGRADED_AFTER = Duration.ofSeconds(20);
    private static final Duration FAILED_AFTER = Duration.ofSeconds(40);

    private final SwarmRegistry registry;
    private final ObjectMapper mapper;

    public ControllerStatusListener(SwarmRegistry registry, ObjectMapper mapper) {
        this.registry = registry;
        this.mapper = mapper.findAndRegisterModules();
    }

    @RabbitListener(queues = "#{controllerStatusQueue.name}")
    public void handle(String body, @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey) {
        if (routingKey == null) return;
        log.info("[CTRL] RECV rk={} payload={}", routingKey, snippet(body));
        try {
            JsonNode node = mapper.readTree(body);
            String swarmId = node.path("swarmId").asText(null);
            JsonNode data = node.path("data");
            String status = data.path("swarmStatus").asText(null);
            if (swarmId != null && status != null) {
                SwarmHealth health = map(status);
                registry.refresh(swarmId, health);
            }
            if (swarmId != null) {
                JsonNode state = data.path("state");
                JsonNode workloads = state.path("workloads");
                if (!workloads.isMissingNode() && workloads.has("enabled")) {
                    registry.updateWorkEnabled(swarmId, workloads.path("enabled").asBoolean());
                }
                JsonNode controller = state.path("controller");
                if (!controller.isMissingNode() && controller.has("enabled")) {
                    registry.updateControllerEnabled(swarmId, controller.path("enabled").asBoolean());
                }
            }
        } catch (Exception e) {
            log.warn("status parse", e);
        }
    }

    private SwarmHealth map(String s) {
        if (s == null) return SwarmHealth.UNKNOWN;
        if ("RUNNING".equalsIgnoreCase(s)) return SwarmHealth.RUNNING;
        if ("FAILED".equalsIgnoreCase(s)) return SwarmHealth.FAILED;
        if ("DEGRADED".equalsIgnoreCase(s)) return SwarmHealth.DEGRADED;
        return SwarmHealth.DEGRADED;
    }

    @Scheduled(fixedRate = 5000L)
    public void expire() {
        registry.expire(DEGRADED_AFTER, FAILED_AFTER);
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
