package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SwarmIoStateAggregatorTest {

  private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

  @Test
  void aggregatesWorstCaseIoStateAcrossWorkers() throws Exception {
    SwarmIoStateAggregator aggregator = new SwarmIoStateAggregator();

    JsonNode ok = MAPPER.readTree("""
        {
          "ioState": { "work": { "input": "ok", "output": "ok" } }
        }
        """);
    JsonNode outOfData = MAPPER.readTree("""
        {
          "ioState": { "work": { "input": "out-of-data", "output": "ok" } }
        }
        """);
    JsonNode upstreamError = MAPPER.readTree("""
        {
          "ioState": { "work": { "input": "upstream-error", "output": "ok" } }
        }
        """);

    aggregator.updateFromWorkerStatus("generator", "w1", ok);
    aggregator.updateFromWorkerStatus("processor", "w2", outOfData);
    aggregator.updateFromWorkerStatus("moderator", "w3", upstreamError);

    SwarmIoStateAggregator.IoState state = aggregator.aggregateWork();
    assertThat(state.input()).isEqualTo("upstream-error");
    assertThat(state.output()).isEqualTo("ok");
  }

  @Test
  void ignoresInvalidOrMissingIoStatePayloads() throws Exception {
    SwarmIoStateAggregator aggregator = new SwarmIoStateAggregator();

    JsonNode invalid = MAPPER.readTree("""
        { "ioState": { "work": { "input": "nope", "output": "also-nope" } } }
        """);
    JsonNode missing = MAPPER.readTree("""
        { "data": 123 }
        """);

    aggregator.updateFromWorkerStatus("generator", "w1", invalid);
    aggregator.updateFromWorkerStatus("generator", "w2", missing);
    aggregator.updateFromWorkerStatus(" ", "w3", missing);
    aggregator.updateFromWorkerStatus("generator", " ", missing);

    SwarmIoStateAggregator.IoState state = aggregator.aggregateWork();
    assertThat(state.input()).isEqualTo("unknown");
    assertThat(state.output()).isEqualTo("unknown");
  }
}

