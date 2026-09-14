package io.pockethive.controlplane.spring;

import jakarta.validation.Valid;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Responsibility: bind worker identity/settings and retain the owner's read-only topology projection.
 * Must not: construct queue names or independently define routing catalogs.
 * Contract: RESP-WORK-RESOURCE-NAMES — docs/architecture/runtime-responsibilities.md#resp-work-resource-names.
 */
@Validated
@ConfigurationProperties(prefix = "pockethive.control-plane")
public final class WorkerControlPlaneProperties {

    private final boolean enabled;
    private final boolean declareTopology;

    private final String exchange;
    private final String swarmId;
    private final String instanceId;
    private final String controlQueuePrefix;
    private final Worker worker;
    private final WorkerControlTopology controlPlane;

    public WorkerControlPlaneProperties(Boolean enabled,
                                        Boolean declareTopology,
                                        String exchange,
                                        String swarmId,
                                        String instanceId,
                                        String controlQueuePrefix,
                                        @Valid Worker worker) {
        this.enabled = enabled == null || enabled;
        this.declareTopology = declareTopology == null || declareTopology;
        this.exchange = requireNonBlank(exchange, "pockethive.control-plane.exchange");
        this.swarmId = requireNonBlank(swarmId, "pockethive.control-plane.swarm-id");
        this.instanceId = requireNonBlank(instanceId, "pockethive.control-plane.instance-id");
        this.controlQueuePrefix = requireNonBlank(controlQueuePrefix,
            "pockethive.control-plane.control-queue-prefix");
        this.worker = Objects.requireNonNull(worker, "worker must not be null");
        this.controlPlane = WorkerControlTopology.forWorker(this.swarmId, this.controlQueuePrefix,
            this.worker.getRole(), this.instanceId);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isDeclareTopology() {
        return declareTopology;
    }

    public String getExchange() {
        return exchange;
    }

    public String getSwarmId() {
        return swarmId;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public String getControlQueuePrefix() {
        return controlQueuePrefix;
    }

    public Worker getWorker() {
        return worker;
    }

    public WorkerControlTopology getControlPlane() {
        return controlPlane;
    }

    @Validated
    public static final class Worker {

        private final boolean enabled;
        private final boolean declareTopology;
        private final String role;
        private final Listener listener;
        private final boolean skipSelfSignals;
        private final DuplicateCache duplicateCache;

        public Worker(Boolean enabled,
                      Boolean declareTopology,
                      String role,
                      @Valid Listener listener,
                      Boolean skipSelfSignals,
                      @Valid DuplicateCache duplicateCache) {
            this.enabled = enabled == null || enabled;
            this.declareTopology = declareTopology == null || declareTopology;
            this.role = requireNonBlank(role, "pockethive.control-plane.worker.role");
            this.listener = listener != null ? listener : new Listener(null);
            this.skipSelfSignals = skipSelfSignals == null || skipSelfSignals;
            this.duplicateCache = duplicateCache != null ? duplicateCache : new DuplicateCache(null, null, null);
        }

        public boolean isEnabled() {
            return enabled;
        }

        public boolean isDeclareTopology() {
            return declareTopology;
        }

        public String getRole() {
            return role;
        }

        public Listener getListener() {
            return listener;
        }

        public boolean isSkipSelfSignals() {
            return skipSelfSignals;
        }

        public DuplicateCache getDuplicateCache() {
            return duplicateCache;
        }

        @Validated
        public static final class Listener {
            private final boolean enabled;

            public Listener(Boolean enabled) {
                this.enabled = enabled == null || enabled;
            }

            public boolean isEnabled() {
                return enabled;
            }
        }

        @Validated
        public static final class DuplicateCache {
            private final boolean enabled;
            private final Duration ttl;
            private final int capacity;

            public DuplicateCache(Boolean enabled, Duration ttl, Integer capacity) {
                this.enabled = enabled == null || enabled;
                this.ttl = ttl != null ? ttl : Duration.ofMinutes(5);
                int resolvedCapacity = capacity != null ? capacity : 1024;
                if (resolvedCapacity <= 0) {
                    throw new IllegalArgumentException(
                        "pockethive.control-plane.worker.duplicate-cache.capacity must be positive");
                }
                this.capacity = resolvedCapacity;
            }

            public boolean isEnabled() {
                return enabled;
            }

            public Duration getTtl() {
                return ttl;
            }

            public int getCapacity() {
                return capacity;
            }
        }
    }

    private static String requireNonBlank(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(property + " must not be null or blank");
        }
        return value;
    }

}
