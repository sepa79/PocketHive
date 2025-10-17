package io.pockethive.controlplane.spring;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties that drive the control-plane auto-configuration.
 */
@ConfigurationProperties(prefix = "pockethive.control-plane")
public class ControlPlaneProperties {

    private boolean enabled = true;
    private boolean declareTopology = true;
    private String exchange = resolve("POCKETHIVE_CONTROL_PLANE_EXCHANGE");
    private String swarmId = resolve("POCKETHIVE_CONTROL_PLANE_SWARM_ID");
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
        this.exchange = exchange;
    }

    public String getSwarmId() {
        return swarmId;
    }

    public void setSwarmId(String swarmId) {
        this.swarmId = swarmId;
    }

    public PublisherProperties getPublisher() {
        return publisher;
    }

    public WorkerProperties getWorker() {
        return worker;
    }

    public ManagerProperties getManager() {
        return manager;
    }

    private static String resolve(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String env = System.getenv(key);
        if (env != null && !env.isBlank()) {
            return env;
        }
        String property = System.getProperty(key);
        if (property != null && !property.isBlank()) {
            return property;
        }
        return null;
    }

    String resolveSwarmId(String override) {
        if (override != null && !override.isBlank()) {
            return override;
        }
        return swarmId;
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
        private String swarmId;
        private String role;
        private String instanceId;

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

        public String getSwarmId() {
            return swarmId;
        }

        public void setSwarmId(String swarmId) {
            this.swarmId = swarmId;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public String getInstanceId() {
            return instanceId;
        }

        public void setInstanceId(String instanceId) {
            this.instanceId = instanceId;
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
}
