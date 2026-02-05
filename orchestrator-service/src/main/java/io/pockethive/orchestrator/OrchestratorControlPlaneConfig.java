package io.pockethive.orchestrator;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.payload.RoleContext;
import io.pockethive.controlplane.spring.ControlPlaneProperties;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.orchestrator.config.OrchestratorProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OrchestratorControlPlaneConfig {

    private final OrchestratorProperties properties;

    OrchestratorControlPlaneConfig(
        ControlPlaneProperties controlPlaneProperties,
        OrchestratorProperties orchestratorProperties) {
        Objects.requireNonNull(controlPlaneProperties, "controlPlaneProperties");
        this.properties = Objects.requireNonNull(orchestratorProperties, "orchestratorProperties");
    }

    @Bean
    ControlPlaneEmitter orchestratorControlPlaneEmitter(
        @Qualifier("managerControlPlaneTopologyDescriptor") ControlPlaneTopologyDescriptor descriptor,
        @Qualifier("managerControlPlaneIdentity") ControlPlaneIdentity identity,
        ControlPlanePublisher publisher) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(publisher, "publisher");
        RoleContext role = RoleContext.fromIdentity(identity);
        return ControlPlaneEmitter.using(descriptor, role, publisher, runtimeMeta());
    }

    @Bean(name = "managerControlQueueName")
    String managerControlQueueName(@Qualifier("managerControlPlaneIdentity") ControlPlaneIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        String prefix = properties.getControlQueuePrefix();
        return queueName(prefix, identity.instanceId());
    }

    @Bean(name = "controllerStatusQueueName")
    String controllerStatusQueueName(@Qualifier("managerControlPlaneIdentity") ControlPlaneIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        String prefix = properties.getStatusQueuePrefix();
        return queueName(prefix, identity.instanceId());
    }

    private static String queueName(String prefix, String instanceId) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalStateException("Queue prefix must not be null or blank");
        }
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalStateException("Control-plane instance id must not be null or blank");
        }
        return prefix + "." + instanceId;
    }

    private static Map<String, Object> runtimeMeta() {
        String templateId = requireText(trimToNull(System.getenv("POCKETHIVE_TEMPLATE_ID")), "POCKETHIVE_TEMPLATE_ID");
        String runId = requireText(trimToNull(System.getenv("POCKETHIVE_JOURNAL_RUN_ID")), "POCKETHIVE_JOURNAL_RUN_ID");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("templateId", templateId);
        meta.put("runId", runId);
        String containerId = trimToNull(System.getenv("HOSTNAME"));
        if (containerId != null) {
            meta.put("containerId", containerId);
        }
        String image = trimToNull(System.getenv("POCKETHIVE_RUNTIME_IMAGE"));
        if (image != null) {
            meta.put("image", image);
        }
        String stackName = trimToNull(System.getenv("POCKETHIVE_RUNTIME_STACK_NAME"));
        if (stackName != null) {
            meta.put("stackName", stackName);
        }
        return Map.copyOf(meta);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + field);
        }
        return value;
    }
}
