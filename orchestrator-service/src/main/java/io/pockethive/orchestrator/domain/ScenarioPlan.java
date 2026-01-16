package io.pockethive.orchestrator.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.SwarmPlan;
import io.pockethive.swarm.model.SwarmTemplate;
import io.pockethive.swarm.model.Topology;
import io.pockethive.swarm.model.TrafficPolicy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scenario description that can be expanded into a SwarmPlan.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScenarioPlan(SwarmTemplate template,
                           Topology topology,
                           TrafficPolicy trafficPolicy,
                           Plan plan) {

  public SwarmPlan toSwarmPlan(String id) {
    List<Bee> bees = template == null || template.bees() == null
        ? List.of()
        : template.bees().stream()
            .map(ScenarioPlan::mergeWorkerConfig)
            .toList();
    return new SwarmPlan(id, bees, topology, trafficPolicy, null, null);
  }

    /**
     * Normalises per-bee configuration into the flat map expected by the worker control-plane
     * runtime.
     * <p>
     * Scenarios authored with either the legacy shape:
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
     * or the newer, direct worker block:
     *
     * <pre>{@code
     * config:
     *   worker:
     *     ratePerSec: 50
     *     message:
     *       path: /api/test
     * }</pre>
     *
     * become a {@code Bee.config} map that includes {@code ratePerSec}, {@code message}, and any
     * other worker-scoped fields directly, so worker DTOs can bind them. Any {@code inputs},
     * {@code outputs}, or {@code interceptors} blocks remain nested for specialised components
     * to inspect.
     */
  private static Bee mergeWorkerConfig(Bee bee) {
    if (bee == null) {
      return null;
    }
    Map<String, Object> config = bee.config();
    if (config == null || config.isEmpty()) {
      return bee;
    }
    Map<String, Object> merged = new LinkedHashMap<>(config);

    // Prefer the new shape: config.worker.{...}
    Object workerBlock = config.get("worker");
    if (workerBlock instanceof Map<?, ?> workerMapRaw) {
      Map<String, Object> workerMap = copyToStringKeyMap(workerMapRaw);
      Map<String, Object> flattened = flattenWorkerBlock(workerMap);
      if (!flattened.isEmpty()) {
        merged.remove("worker");
        merged.putAll(flattened);
        return new Bee(bee.id(), bee.role(), bee.image(), bee.work(), bee.ports(), bee.env(), merged);
      }
    }

    // Fallback to the legacy shape: config.pockethive.worker.config.{...}
    Object pockethiveObj = config.get("pockethive");
    if (!(pockethiveObj instanceof Map<?, ?> pockethive)) {
      return bee;
    }
    Object workerObj = pockethive.get("worker");
    if (!(workerObj instanceof Map<?, ?> workerRaw)) {
      return bee;
    }
    Map<String, Object> workerMap = copyToStringKeyMap(workerRaw);
    Map<String, Object> flattened = flattenWorkerBlock(workerMap);
    if (flattened.isEmpty()) {
      return bee;
    }

    merged.remove("pockethive");
    merged.putAll(flattened);

    return new Bee(bee.id(), bee.role(), bee.image(), bee.work(), bee.ports(), bee.env(), merged);
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

  /**
   * Flattens a worker configuration block so the {@code config} sub-object (when present) is
   * merged into the same level as other worker-scoped fields such as {@code historyPolicy}.
   */
  private static Map<String, Object> flattenWorkerBlock(Map<String, Object> workerMap) {
    if (workerMap == null || workerMap.isEmpty()) {
      return Map.of();
    }
    Map<String, Object> flattened = new LinkedHashMap<>();
    workerMap.forEach((key, value) -> {
      if ("config".equals(key) && value instanceof Map<?, ?> nested) {
        flattened.putAll(copyToStringKeyMap(nested));
      } else {
        flattened.put(key, value);
      }
    });
    return flattened.isEmpty() ? Map.of() : Map.copyOf(flattened);
  }

  /**
   * Scenario execution plan (optional).
   * <p>
   * The plan is intentionally a thin DTO that mirrors the YAML contract; scheduling and
   * validation live in the swarm-manager runtime.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Plan(List<PlanBee> bees,
                     List<SwarmStep> swarm) {
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PlanBee(String instanceId,
                        String role,
                        List<Step> steps) {
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Step(String stepId,
                     String name,
                     String time,
                     String type,
                     Map<String, Object> config) {

    public Step {
      config = config == null ? Map.of() : Map.copyOf(config);
    }
  }

  /**
   * Swarm-level step. Semantics are defined by {@code type}:
   * <ul>
   *   <li>{@code swarm-start} / {@code swarm-stop} – manager-level lifecycle commands.</li>
   *   <li>Other values are reserved for future use.</li>
   * </ul>
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SwarmStep(String stepId,
                          String name,
                          String time,
                          String type,
                          Map<String, Object> config) {

    public SwarmStep {
      config = config == null ? Map.of() : Map.copyOf(config);
    }
  }
}
