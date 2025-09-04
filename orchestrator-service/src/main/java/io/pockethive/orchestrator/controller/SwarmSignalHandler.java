package io.pockethive.orchestrator.controller;

import io.pockethive.orchestrator.model.SwarmTemplate;
import io.pockethive.orchestrator.model.SwarmStatus;
import io.pockethive.orchestrator.service.SwarmOrchestrator;
import io.pockethive.orchestrator.service.SwarmRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SwarmSignalHandler {
    private static final Logger logger = LoggerFactory.getLogger(SwarmSignalHandler.class);

    @Autowired
    private SwarmOrchestrator swarmOrchestrator;

    @Autowired
    private SwarmRegistry swarmRegistry;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @RabbitListener(queues = "ph.control.orchestrator.#{T(java.util.UUID).randomUUID().toString()}")
    public void handleSwarmSignals(String message) {
        try {
            logger.debug("Received signal: {}", message);
            
            // Parse the signal message
            var signalData = objectMapper.readTree(message);
            String routingKey = signalData.path("routingKey").asText();
            
            if (routingKey.startsWith("sig.swarm-create.")) {
                handleSwarmCreate(routingKey, signalData);
            } else if (routingKey.startsWith("sig.swarm-destroy.")) {
                handleSwarmDestroy(routingKey, signalData);
            } else if (routingKey.equals("sig.swarm-list")) {
                handleSwarmList();
            }
            
        } catch (Exception e) {
            logger.error("Error handling swarm signal: {}", e.getMessage(), e);
        }
    }

    private void handleSwarmCreate(String routingKey, com.fasterxml.jackson.databind.JsonNode signalData) {
        try {
            // Extract swarm ID from routing key: sig.swarm-create.{swarmId}
            String swarmId = routingKey.substring("sig.swarm-create.".length());
            
            // Create default REST swarm template
            SwarmTemplate template = new SwarmTemplate(swarmId, "REST");
            
            // Override with any provided configuration
            if (signalData.has("data")) {
                var data = signalData.get("data");
                if (data.has("swarmType")) {
                    template.setSwarmType(data.get("swarmType").asText());
                }
                if (data.has("targetTPS")) {
                    template.setTargetTPS(data.get("targetTPS").asInt());
                }
            }
            
            SwarmStatus status = swarmOrchestrator.createSwarm(template);
            
            // Publish swarm creation event
            publishSwarmEvent("ev.swarm-created.orchestrator", swarmId, status);
            
        } catch (Exception e) {
            logger.error("Failed to create swarm from signal {}: {}", routingKey, e.getMessage());
            publishSwarmEvent("ev.swarm-failed.orchestrator", 
                routingKey.substring("sig.swarm-create.".length()), 
                "Failed to create swarm: " + e.getMessage());
        }
    }

    private void handleSwarmDestroy(String routingKey, com.fasterxml.jackson.databind.JsonNode signalData) {
        try {
            // Extract swarm ID from routing key: sig.swarm-destroy.{swarmId}
            String swarmId = routingKey.substring("sig.swarm-destroy.".length());
            
            swarmOrchestrator.destroySwarm(swarmId);
            
            // Publish swarm destruction event
            publishSwarmEvent("ev.swarm-destroyed.orchestrator", swarmId, "Swarm destroyed successfully");
            
        } catch (Exception e) {
            logger.error("Failed to destroy swarm from signal {}: {}", routingKey, e.getMessage());
            publishSwarmEvent("ev.swarm-failed.orchestrator", 
                routingKey.substring("sig.swarm-destroy.".length()), 
                "Failed to destroy swarm: " + e.getMessage());
        }
    }

    private void handleSwarmList() {
        try {
            var swarms = swarmRegistry.getAllSwarms();
            publishSwarmEvent("ev.swarm-list.orchestrator", "all", swarms);
        } catch (Exception e) {
            logger.error("Failed to list swarms: {}", e.getMessage());
        }
    }

    private void publishSwarmEvent(String eventType, String swarmId, Object data) {
        try {
            var event = objectMapper.createObjectNode();
            event.put("event", eventType);
            event.put("swarmId", swarmId);
            event.put("timestamp", System.currentTimeMillis());
            event.set("data", objectMapper.valueToTree(data));
            
            rabbitTemplate.convertAndSend("ph.control", eventType, event.toString());
            logger.debug("Published event: {} for swarm: {}", eventType, swarmId);
            
        } catch (Exception e) {
            logger.error("Failed to publish swarm event: {}", e.getMessage());
        }
    }
}