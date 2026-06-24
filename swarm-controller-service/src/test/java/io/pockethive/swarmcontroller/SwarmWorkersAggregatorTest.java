package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SwarmWorkersAggregatorTest {

  private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

  @Test
  void propagatesWorkerStatusFullConfigIntoSnapshot() throws Exception {
    SwarmWorkersAggregator aggregator = new SwarmWorkersAggregator(60_000);

    aggregator.updateFromWorkerStatus(
        "generator",
        "gen-1",
        data("""
            {
              "enabled": true,
              "tps": 42,
              "config": {
                "enabled": true,
                "message": {
                  "method": "POST",
                  "path": "/test"
                },
                "inputs": {
                  "scheduler": {
                    "ratePerSec": 50
                  }
                }
              },
              "ioState": {
                "work": {
                  "input": "ok",
                  "output": "ok"
                }
              }
            }
            """),
        runtime());

    Map<String, Object> worker = singleWorker(aggregator.snapshot());

    assertThat(worker).containsKey("config");
    assertThat(config(worker))
        .containsEntry("enabled", true)
        .containsKeys("message", "inputs");
  }

  @Test
  void keepsLastReportedConfigWhenLaterStatusOmitsConfig() throws Exception {
    SwarmWorkersAggregator aggregator = new SwarmWorkersAggregator(60_000);

    aggregator.updateFromWorkerStatus(
        "generator",
        "gen-1",
        data("""
            {
              "enabled": true,
              "config": {
                "inputs": {
                  "scheduler": {
                    "ratePerSec": 50
                  }
                }
              },
              "ioState": {
                "work": {
                  "input": "ok",
                  "output": "ok"
                }
              }
            }
            """),
        runtime());

    aggregator.updateFromWorkerStatus(
        "generator",
        "gen-1",
        data("""
            {
              "enabled": false,
              "tps": 7,
              "ioState": {
                "work": {
                  "input": "backpressure",
                  "output": "ok"
                }
              }
            }
            """),
        null);

    Map<String, Object> worker = singleWorker(aggregator.snapshot());

    assertThat(worker).containsEntry("enabled", false);
    assertThat(config(worker)).containsKey("inputs");
  }

  @Test
  void preservesExplicitEmptyConfigFromStatusFull() throws Exception {
    SwarmWorkersAggregator aggregator = new SwarmWorkersAggregator(60_000);

    aggregator.updateFromWorkerStatus(
        "postprocessor",
        "post-1",
        data("""
            {
              "enabled": true,
              "config": {
                "mode": "old"
              },
              "ioState": {
                "work": {
                  "input": "ok",
                  "output": "ok"
                }
              }
            }
            """),
        runtime());

    aggregator.updateFromWorkerStatus(
        "postprocessor",
        "post-1",
        data("""
            {
              "enabled": true,
              "config": {},
              "ioState": {
                "work": {
                  "input": "ok",
                  "output": "ok"
                }
              }
            }
            """),
        runtime());

    Map<String, Object> worker = singleWorker(aggregator.snapshot());

    assertThat(worker).containsKey("config");
    assertThat(config(worker)).isEmpty();
  }

  private static JsonNode data(String json) throws Exception {
    return MAPPER.readTree(json);
  }

  private static JsonNode runtime() throws Exception {
    return MAPPER.readTree("""
        {
          "templateId": "local-rest",
          "runId": "run-1",
          "containerId": "container-1",
          "image": "worker:latest",
          "stackName": "ph-swarm"
        }
        """);
  }

  private static Map<String, Object> singleWorker(List<Map<String, Object>> workers) {
    assertThat(workers).hasSize(1);
    return workers.get(0);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> config(Map<String, Object> worker) {
    assertThat(worker.get("config")).isInstanceOf(Map.class);
    return (Map<String, Object>) worker.get("config");
  }
}
