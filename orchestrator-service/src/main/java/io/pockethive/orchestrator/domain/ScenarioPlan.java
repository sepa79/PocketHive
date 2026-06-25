package io.pockethive.orchestrator.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.SwarmPlan;
import io.pockethive.swarm.model.SwarmTemplate;
import io.pockethive.swarm.model.Topology;
import io.pockethive.swarm.model.TrafficPolicy;
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
        : template.bees().stream().toList();
    return new SwarmPlan(id, bees, topology, trafficPolicy, null, null);
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
