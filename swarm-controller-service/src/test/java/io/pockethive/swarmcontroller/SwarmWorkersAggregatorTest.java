package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SwarmWorkersAggregatorTest {

  private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

  @Test
  void propagatesWorkerStatusFullConfigIntoSnapshot() throws Exception {
    SwarmWorkersAggregator aggregator = new SwarmWorkersAggregator(60_000);
    Map<String, Object> expectedConfig = Map.of(
        "enabled", true,
        "message", Map.of(
            "method", "POST",
            "path", "/test"
        ),
        "inputs", Map.of(
            "scheduler", Map.of("ratePerSec", 50)
        )
    );

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
                  "input": "SCHEDULER",
                  "output": "RABBITMQ"
                }
              }
            }
            """),
        runtime());

    Map<String, Object> worker = singleWorker(aggregator.snapshot());

    assertThat(worker).containsKey("config");
    assertThat(config(worker)).isEqualTo(expectedConfig);
    @SuppressWarnings("unchecked")
    Map<String, Object> inputs = (Map<String, Object>) config(worker).get("inputs");
    assertThat(inputs).doesNotContainKey("type");
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

  @Test
  void includesControllerOwnedBeeIdForDuplicateRolesWithoutWorkerEcho() throws Exception {
    SwarmWorkersAggregator aggregator = aggregatorWithRuntimeBeeIds(Map.of(
        key("generator", "gen-a"), "runtime-bee-a",
        key("generator", "gen-b"), "runtime-bee-b"));

    aggregator.updateFromWorkerStatus(
        "generator",
        "gen-a",
        data("""
            {
              "enabled": true,
              "tps": 11,
              "config": { "message": { "path": "/alpha" } },
              "ioState": { "work": { "input": "ok", "output": "ok" } }
            }
            """),
        runtime());
    aggregator.updateFromWorkerStatus(
        "generator",
        "gen-b",
        data("""
            {
              "enabled": true,
              "tps": 29,
              "config": { "message": { "path": "/beta" } },
              "ioState": { "work": { "input": "ok", "output": "ok" } }
            }
            """),
        runtime());

    List<Map<String, Object>> workers = aggregator.snapshot();

    assertThat(workers)
        .extracting(worker -> worker.get("beeId"))
        .containsExactlyInAnyOrder("runtime-bee-a", "runtime-bee-b");
    assertThat(workers)
        .filteredOn(worker -> "runtime-bee-a".equals(worker.get("beeId")))
        .singleElement()
        .satisfies(worker -> assertThat(config(worker)).containsEntry("message", Map.of("path", "/alpha")));
    assertThat(workers)
        .filteredOn(worker -> "runtime-bee-b".equals(worker.get("beeId")))
        .singleElement()
        .satisfies(worker -> assertThat(config(worker)).containsEntry("message", Map.of("path", "/beta")));
  }

  @Test
  void workerContextBeeIdEchoDoesNotOverrideControllerOwnedMapping() throws Exception {
    SwarmWorkersAggregator aggregator = aggregatorWithRuntimeBeeIds(Map.of(
        key("generator", "gen-a"), "runtime-bee-a"));

    aggregator.updateFromWorkerStatus(
        "generator",
        "gen-a",
        data("""
            {
              "enabled": true,
              "context": { "beeId": "worker-echo-wrong" },
              "config": { "message": { "path": "/alpha" } },
              "ioState": { "work": { "input": "ok", "output": "ok" } }
            }
            """),
        runtime());

    Map<String, Object> worker = singleWorker(aggregator.snapshot());

    assertThat(worker)
        .containsEntry("beeId", "runtime-bee-a")
        .doesNotContainEntry("beeId", "worker-echo-wrong");
  }

  private static JsonNode data(String json) throws Exception {
    return MAPPER.readTree(json);
  }

  private static SwarmWorkersAggregator aggregatorWithRuntimeBeeIds(Map<String, String> beeIdsByScope) {
    BiFunction<String, String, Optional<String>> resolver =
        (role, instance) -> Optional.ofNullable(beeIdsByScope.get(key(role, instance)));
    return new SwarmWorkersAggregator(60_000L, resolver);
  }

  private static String key(String role, String instance) {
    return role + ":" + instance;
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
