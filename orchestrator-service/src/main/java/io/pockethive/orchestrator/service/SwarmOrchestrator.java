package io.pockethive.orchestrator.service;

import io.pockethive.orchestrator.model.SwarmTemplate;
import io.pockethive.orchestrator.model.SwarmStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class SwarmOrchestrator {
    private static final Logger logger = LoggerFactory.getLogger(SwarmOrchestrator.class);

    @Autowired
    private DockerService dockerService;

    @Autowired
    private SwarmRegistry swarmRegistry;

    public SwarmStatus createSwarm(SwarmTemplate template) {
        String swarmId = template.getSwarmId();
        logger.info("Creating swarm: {}", swarmId);

        if (swarmRegistry.swarmExists(swarmId)) {
            throw new IllegalArgumentException("Swarm already exists: " + swarmId);
        }

        SwarmStatus swarmStatus = new SwarmStatus(swarmId, SwarmStatus.Status.CREATING);
        swarmRegistry.registerSwarm(swarmStatus);

        try {
            List<String> containerIds = new ArrayList<>();

            // Create containers for each component
            for (String component : template.getComponents()) {
                String containerName = component + "-" + swarmId;
                String imageName = "pockethive/" + component + "-service";
                
                Map<String, String> environment = createEnvironment(template, component);
                
                String containerId = dockerService.createContainer(imageName, containerName, environment);
                dockerService.startContainer(containerId);
                containerIds.add(containerId);
            }

            swarmStatus.setContainerIds(containerIds);
            swarmStatus.setStatus(SwarmStatus.Status.RUNNING);
            logger.info("Successfully created swarm: {}", swarmId);

        } catch (Exception e) {
            logger.error("Failed to create swarm {}: {}", swarmId, e.getMessage());
            swarmStatus.setStatus(SwarmStatus.Status.FAILED);
            swarmStatus.setErrorMessage(e.getMessage());
        }

        return swarmStatus;
    }

    public void destroySwarm(String swarmId) {
        logger.info("Destroying swarm: {}", swarmId);
        
        SwarmStatus swarmStatus = swarmRegistry.getSwarm(swarmId);
        if (swarmStatus == null) {
            throw new IllegalArgumentException("Swarm not found: " + swarmId);
        }

        swarmStatus.setStatus(SwarmStatus.Status.STOPPING);

        try {
            if (swarmStatus.getContainerIds() != null) {
                for (String containerId : swarmStatus.getContainerIds()) {
                    dockerService.stopContainer(containerId);
                    dockerService.removeContainer(containerId);
                }
            }

            swarmRegistry.removeSwarm(swarmId);
            logger.info("Successfully destroyed swarm: {}", swarmId);

        } catch (Exception e) {
            logger.error("Failed to destroy swarm {}: {}", swarmId, e.getMessage());
            swarmStatus.setStatus(SwarmStatus.Status.FAILED);
            swarmStatus.setErrorMessage(e.getMessage());
        }
    }

    private Map<String, String> createEnvironment(SwarmTemplate template, String component) {
        Map<String, String> env = new HashMap<>();
        env.put("PH_SWARM_ID", template.getSwarmId());
        env.put("PH_SWARM_TYPE", template.getSwarmType());
        env.put("RABBITMQ_HOST", "rabbitmq");
        
        // Add any custom environment variables from template
        if (template.getEnvironment() != null) {
            env.putAll(template.getEnvironment());
        }
        
        return env;
    }
}