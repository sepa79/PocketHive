package io.pockethive.orchestrator.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.SwarmPlan;
import io.pockethive.swarm.model.SwarmTemplate;
import io.pockethive.swarm.model.TrafficPolicy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scenario description that can be expanded into a SwarmPlan.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScenarioPlan(SwarmTemplate template,
                           TrafficPolicy trafficPolicy) {
    public SwarmPlan toSwarmPlan(String id) {
        List<Bee> bees = template == null || template.bees() == null
            ? List.of()
            : template.bees().stream()
                .map(ScenarioPlan::mergeWorkerConfig)
                .toList();
        return new SwarmPlan(id, bees, trafficPolicy);
    }

    /**
     * Merges any {@code pockethive.worker.config} block declared on the bee into the
     * flat config map expected by the worker control-plane runtime.
     * <p>
     * Scenarios authored with:
     *
     * <pre>{@code
     * config:
     *   pockethive:
     *     worker:
     *       config:
     *         ratePerSec: 50
     *         message:
     *           path: /api/test
     * }</pre>
     *
     * become a {@code Bee.config} map that includes {@code ratePerSec}, {@code message}, etc.,
     * so worker DTOs can bind them directly.
     */
    private static Bee mergeWorkerConfig(Bee bee) {
        if (bee == null) {
            return null;
        }
        Map<String, Object> config = bee.config();
        if (config == null || config.isEmpty()) {
            return bee;
        }
        Object pockethiveObj = config.get("pockethive");
        if (!(pockethiveObj instanceof Map<?, ?> pockethive)) {
            return bee;
        }
        Object workerObj = pockethive.get("worker");
        if (!(workerObj instanceof Map<?, ?> worker)) {
            return bee;
        }
        Map<String, Object> workerMap = copyToStringKeyMap(worker);
        Object workerConfigObj = workerMap.get("config");
        if (!(workerConfigObj instanceof Map<?, ?> workerConfigRaw)) {
            return bee;
        }
        Map<String, Object> workerConfig = copyToStringKeyMap(workerConfigRaw);
        if (workerConfig.isEmpty()) {
            return bee;
        }

        Map<String, Object> merged = new LinkedHashMap<>(config);
        // Scenario-supplied worker config should be the single source of truth;
        // flatten it into the top-level map and drop the nested helper structure.
        merged.remove("pockethive");
        merged.putAll(workerConfig);

        return new Bee(bee.role(), bee.image(), bee.work(), bee.env(), merged);
    }

    private static Map<String, Object> copyToStringKeyMap(Map<?, ?> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null) {
                copy.put(key.toString(), value);
            }
        });
        return copy.isEmpty() ? Map.of() : Map.copyOf(copy);
    }
}
