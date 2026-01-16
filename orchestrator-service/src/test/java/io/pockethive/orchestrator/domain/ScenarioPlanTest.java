package io.pockethive.orchestrator.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.orchestrator.app.JacksonConfiguration;
import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.BeePort;
import io.pockethive.swarm.model.SwarmPlan;
import io.pockethive.swarm.model.SwarmTemplate;
import io.pockethive.swarm.model.Topology;
import io.pockethive.swarm.model.TopologyEdge;
import io.pockethive.swarm.model.TopologyEndpoint;
import io.pockethive.swarm.model.Work;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScenarioPlanTest {

  private final ObjectMapper mapper = new JacksonConfiguration().objectMapper();

  @Test
  void deserializesPlanWithBeesAndSteps() throws Exception {
    String json = """
        {
          "template": null,
          "trafficPolicy": null,
          "plan": {
            "swarm": [
              {
                "stepId": "sw1",
                "name": "Stop swarm",
                "time": "PT300S",
                "type": "swarm-stop"
              }
            ],
            "bees": [
              {
                "instanceId": "seeder-bee",
                "role": "generator",
                "steps": [
                  {
                    "stepId": "s1",
                    "name": "Change rate",
                    "time": "PT15S",
                    "type": "config-update",
                    "config": {
                      "worker": {
                        "ratePerSec": 10
                      }
                    }
                  }
                ]
              }
            ]
          }
        }
        """;

    ScenarioPlan plan = mapper.readValue(json, ScenarioPlan.class);

    assertThat(plan.plan()).isNotNull();
    assertThat(plan.plan().bees()).hasSize(1);

    ScenarioPlan.PlanBee bee = plan.plan().bees().getFirst();
    assertThat(bee.instanceId()).isEqualTo("seeder-bee");
    assertThat(bee.role()).isEqualTo("generator");
    assertThat(bee.steps()).hasSize(1);

    ScenarioPlan.Step step = bee.steps().getFirst();
    assertThat(step.stepId()).isEqualTo("s1");
    assertThat(step.name()).isEqualTo("Change rate");
    assertThat(step.time()).isEqualTo("PT15S");
    assertThat(step.type()).isEqualTo("config-update");

    Map<String, Object> cfg = step.config();
    assertThat(cfg).containsKey("worker");
    Object worker = cfg.get("worker");
    assertThat(worker).isInstanceOf(Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> workerMap = (Map<String, Object>) worker;
    assertThat(workerMap.get("ratePerSec")).isEqualTo(10);

    // Swarm-level step
    assertThat(plan.plan().swarm()).hasSize(1);
    ScenarioPlan.SwarmStep swarmStep = plan.plan().swarm().getFirst();
    assertThat(swarmStep.stepId()).isEqualTo("sw1");
    assertThat(swarmStep.type()).isEqualTo("swarm-stop");
    assertThat(swarmStep.config()).isEmpty();
  }

  @Test
  void toSwarmPlanPreservesTopology() {
    SwarmTemplate template = new SwarmTemplate("controller", List.of(
        new Bee("genA", "generator", "img", Work.ofDefaults(null, "gen"),
            List.of(new BeePort("out", "out")), Map.of(), Map.of()),
        new Bee("modA", "moderator", "img2", Work.ofDefaults("gen", "mod"),
            List.of(new BeePort("in", "in"), new BeePort("out", "out")), Map.of(), Map.of())
    ));
    Topology topology = new Topology(1, List.of(
        new TopologyEdge("e1", new TopologyEndpoint("genA", "out"), new TopologyEndpoint("modA", "in"), null)
    ));
    ScenarioPlan scenarioPlan = new ScenarioPlan(template, topology, null, null);

    SwarmPlan swarmPlan = scenarioPlan.toSwarmPlan("swarm-1");

    assertThat(swarmPlan.topology()).isNotNull();
    assertThat(swarmPlan.topology().edges()).hasSize(1);
    assertThat(swarmPlan.bees().getFirst().id()).isEqualTo("genA");
  }
}
