package io.pockethive.controlplane.spring;

import jakarta.validation.Valid;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Minimal worker-facing control-plane configuration that binds the swarm/instance identity,
 * queue names, logging flags, and Pushgateway settings injected by the orchestrator.
 */
@Validated
@ConfigurationProperties(prefix = "pockethive.control-plane")
public final class WorkerControlPlaneProperties {

    private final boolean enabled;
    private final boolean declareTopology;
    private final String exchange;
    private final String swarmId;
    private final String instanceId;
    private final Queues queues;
    private final Worker worker;
    private final SwarmController swarmController;

    public WorkerControlPlaneProperties(Boolean enabled,
                                        Boolean declareTopology,
                                        String exchange,
                                        String swarmId,
                                        String instanceId,
                                        Map<String, String> queues,
                                        @Valid Worker worker,
                                        @Valid SwarmController swarmController) {
        this.enabled = enabled == null || enabled;
        this.declareTopology = declareTopology == null || declareTopology;
        this.exchange = requireNonBlank(exchange, "pockethive.control-plane.exchange");
        this.swarmId = requireNonBlank(swarmId, "pockethive.control-plane.swarm-id");
        this.instanceId = requireNonBlank(instanceId, "pockethive.control-plane.instance-id");
        this.queues = new Queues(queues);
        this.worker = Objects.requireNonNull(worker, "worker must not be null");
        this.swarmController = Objects.requireNonNull(swarmController, "swarmController must not be null");
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

    public Queues getQueues() {
        return queues;
    }

    public Worker getWorker() {
        return worker;
    }

    public SwarmController getSwarmController() {
        return swarmController;
    }

    @Validated
    public static final class Queues {

        private final Map<String, String> names;

        public Queues(Map<String, String> names) {
            if (names == null || names.isEmpty()) {
                throw new IllegalArgumentException("pockethive.control-plane.queues must define at least one queue");
            }
            Map<String, String> copy = new LinkedHashMap<>();
            names.forEach((key, value) -> {
                String queueKey = key == null ? "" : key.trim();
                if (queueKey.isEmpty()) {
                    throw new IllegalArgumentException("pockethive.control-plane.queues contains an unnamed entry");
                }
                copy.put(queueKey, requireNonBlank(value,
                    "pockethive.control-plane.queues." + queueKey));
            });
            this.names = Collections.unmodifiableMap(copy);
        }

        public Map<String, String> names() {
            return names;
        }

        public String get(String queueName) {
            return names.get(queueName);
        }
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

    @Validated
    public static final class SwarmController {
        private final Rabbit rabbit;
        private final Metrics metrics;

        public SwarmController(@Valid Rabbit rabbit, @Valid Metrics metrics) {
            this.rabbit = Objects.requireNonNull(rabbit, "swarmController.rabbit must not be null");
            this.metrics = Objects.requireNonNull(metrics, "swarmController.metrics must not be null");
        }

        public Rabbit getRabbit() {
            return rabbit;
        }

        public Metrics getMetrics() {
            return metrics;
        }

        @Validated
        public static final class Rabbit {
            private final String logsExchange;
            private final Logging logging;

            public Rabbit(String logsExchange, @Valid Logging logging) {
                this.logsExchange = requireNonBlank(logsExchange,
                    "pockethive.control-plane.swarm-controller.rabbit.logs-exchange");
                this.logging = logging != null ? logging : new Logging(null);
            }

            public String getLogsExchange() {
                return logsExchange;
            }

            public Logging getLogging() {
                return logging;
            }

            @Validated
            public static final class Logging {
                private final boolean enabled;

                public Logging(Boolean enabled) {
                    this.enabled = Boolean.TRUE.equals(enabled);
                }

                public boolean isEnabled() {
                    return enabled;
                }
            }
        }

        @Validated
        public static final class Metrics {
            private final Pushgateway pushgateway;

            public Metrics(@Valid Pushgateway pushgateway) {
                this.pushgateway = Objects.requireNonNull(pushgateway,
                    "pockethive.control-plane.swarm-controller.metrics.pushgateway must not be null");
            }

            public Pushgateway getPushgateway() {
                return pushgateway;
            }

            @Validated
            public static final class Pushgateway {
                private final boolean enabled;
                private final String baseUrl;
                private final Duration pushRate;
                private final String shutdownOperation;

                public Pushgateway(Boolean enabled,
                                   String baseUrl,
                                   Duration pushRate,
                                   String shutdownOperation) {
                    this.enabled = Boolean.TRUE.equals(enabled);
                    this.baseUrl = baseUrl;
                    this.pushRate = Objects.requireNonNull(pushRate,
                        "pockethive.control-plane.swarm-controller.metrics.pushgateway.push-rate must not be null");
                    this.shutdownOperation = requireNonBlank(shutdownOperation,
                        "pockethive.control-plane.swarm-controller.metrics.pushgateway.shutdown-operation");
                }

                public boolean isEnabled() {
                    return enabled;
                }

                public String getBaseUrl() {
                    return baseUrl;
                }

                public Duration getPushRate() {
                    return pushRate;
                }

                public String getShutdownOperation() {
                    return shutdownOperation;
                }
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
