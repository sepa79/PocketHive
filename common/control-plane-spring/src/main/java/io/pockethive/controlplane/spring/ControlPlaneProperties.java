package io.pockethive.controlplane.spring;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties that drive the control-plane auto-configuration.
 */
@Validated
@ConfigurationProperties(prefix = "pockethive.control-plane")
public class ControlPlaneProperties {

    private boolean enabled = true;
    private boolean declareTopology = true;
    @NotBlank
    private String exchange;
    @NotBlank
    private String controlQueuePrefix;
    @Valid
    private final IdentityProperties identity = new IdentityProperties();
    private final PublisherProperties publisher = new PublisherProperties();
    private final WorkerProperties worker = new WorkerProperties();
    private final ManagerProperties manager = new ManagerProperties();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isDeclareTopology() {
        return declareTopology;
    }

    public void setDeclareTopology(boolean declareTopology) {
        this.declareTopology = declareTopology;
    }

    public String getExchange() {
        return exchange;
    }

    public void setExchange(String exchange) {
        this.exchange = requireText(exchange, "pockethive.control-plane.exchange");
    }

    public String getControlQueuePrefix() {
        return controlQueuePrefix;
    }

    public void setControlQueuePrefix(String controlQueuePrefix) {
        this.controlQueuePrefix = requireText(controlQueuePrefix,
            "pockethive.control-plane.control-queue-prefix");
    }

    public PublisherProperties getPublisher() {
        return publisher;
    }

    public IdentityProperties getIdentity() {
        return identity;
    }

    public String getSwarmId() {
        return identity.getSwarmId();
    }

    public void setSwarmId(String swarmId) {
        identity.setSwarmId(swarmId);
    }

    public String getInstanceId() {
        return identity.getInstanceId();
    }

    public void setInstanceId(String instanceId) {
        identity.setInstanceId(instanceId);
    }

    public WorkerProperties getWorker() {
        return worker;
    }

    public ManagerProperties getManager() {
        return manager;
    }

    public static final class PublisherProperties {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class ParticipantProperties {
        private boolean enabled = true;
        private boolean declareTopology = true;
        private String role;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isDeclareTopology() {
            return declareTopology;
        }

        public void setDeclareTopology(boolean declareTopology) {
            this.declareTopology = declareTopology;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }
    }

    public static final class WorkerProperties extends ParticipantProperties {
        private boolean listenerEnabled = true;
        private boolean skipSelfSignals = true;
        private final DuplicateCacheProperties duplicateCache = new DuplicateCacheProperties();

        public boolean isListenerEnabled() {
            return listenerEnabled;
        }

        public void setListenerEnabled(boolean listenerEnabled) {
            this.listenerEnabled = listenerEnabled;
        }

        public boolean isSkipSelfSignals() {
            return skipSelfSignals;
        }

        public void setSkipSelfSignals(boolean skipSelfSignals) {
            this.skipSelfSignals = skipSelfSignals;
        }

        public DuplicateCacheProperties getDuplicateCache() {
            return duplicateCache;
        }
    }

    public static final class ManagerProperties extends ParticipantProperties {
        private boolean listenerEnabled = true;
        private boolean skipSelfSignals = true;
        private final DuplicateCacheProperties duplicateCache = new DuplicateCacheProperties();

        public boolean isListenerEnabled() {
            return listenerEnabled;
        }

        public void setListenerEnabled(boolean listenerEnabled) {
            this.listenerEnabled = listenerEnabled;
        }

        public boolean isSkipSelfSignals() {
            return skipSelfSignals;
        }

        public void setSkipSelfSignals(boolean skipSelfSignals) {
            this.skipSelfSignals = skipSelfSignals;
        }

        public DuplicateCacheProperties getDuplicateCache() {
            return duplicateCache;
        }
    }

    public static final class DuplicateCacheProperties {
        private boolean enabled = true;
        private Duration ttl = Duration.ofMinutes(5);
        private int capacity = 1024;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = Objects.requireNonNull(ttl, "ttl must not be null");
        }

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("duplicate-cache.capacity must be positive");
            }
            this.capacity = capacity;
        }
    }

    @Validated
    public final class IdentityProperties {

        @NotBlank
        private String swarmId;

        @NotBlank
        private String instanceId;

        public String getSwarmId() {
            return swarmId;
        }

        public void setSwarmId(String swarmId) {
            this.swarmId = requireText(swarmId, "pockethive.control-plane.swarm-id");
        }

        public String getInstanceId() {
            return instanceId;
        }

        public void setInstanceId(String instanceId) {
            this.instanceId = requireText(instanceId, "pockethive.control-plane.instance-id");
        }
    }

    private static String requireText(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(property + " must not be null or blank");
        }
        return value;
    }
}
