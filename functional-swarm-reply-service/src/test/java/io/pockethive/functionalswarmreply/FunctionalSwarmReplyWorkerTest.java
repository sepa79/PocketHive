package io.pockethive.functionalswarmreply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.pockethive.functionalswarm.contracts.FunctionalSwarmProtocol;
import io.pockethive.functionalswarm.redis.FunctionalSwarmRedisEndpoint;
import io.pockethive.functionalswarm.redis.FunctionalSwarmRedisTransport;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.worker.sdk.api.HttpResultEnvelope;
import io.pockethive.worker.sdk.api.StatusPublisher;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.api.WorkerInfo;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class FunctionalSwarmReplyWorkerTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final WorkerInfo INFO = new WorkerInfo("functional-swarm-reply", "swarm", "instance", null, null);

  @Test
  void validatesAndPublishesOneCanonicalHttpResponse() throws Exception {
    CapturingTransport transport = new CapturingTransport();
    FunctionalSwarmReplyWorker worker = new FunctionalSwarmReplyWorker(transport);
    String requestId = "00000000-0000-0000-0000-000000000001";
    String prefix = "pockethive.functional.reply.";
    FunctionalSwarmReplyWorkerConfig config = new FunctionalSwarmReplyWorkerConfig(
        new FunctionalSwarmRedisEndpoint("redis", 6379, false, 1_000), prefix, 30);
    WorkItem input = WorkItem.text(INFO, MAPPER.writeValueAsString(httpResult()))
        .headers(Map.of(
            FunctionalSwarmProtocol.REQUEST_ID_HEADER, requestId,
            FunctionalSwarmProtocol.REPLY_LIST_HEADER, prefix + requestId))
        .build();

    assertThat(worker.onMessage(input, new TestContext(config))).isNull();
    assertThat(transport.endpoint).isEqualTo(config.redis());
    assertThat(transport.replyList).isEqualTo(prefix + requestId);
    assertThat(transport.requestId).isEqualTo(requestId);
    assertThat(transport.ttlSeconds).isEqualTo(30);
    assertThat(transport.responsePayload).contains("http.result");
  }

  @Test
  void rejectsAnUntrustedReplyListBeforePublishing() throws Exception {
    CapturingTransport transport = new CapturingTransport();
    FunctionalSwarmReplyWorker worker = new FunctionalSwarmReplyWorker(transport);
    FunctionalSwarmReplyWorkerConfig config = new FunctionalSwarmReplyWorkerConfig(
        new FunctionalSwarmRedisEndpoint("redis", 6379, false, 1_000), "reply.", 30);
    WorkItem input = WorkItem.text(INFO, MAPPER.writeValueAsString(httpResult()))
        .headers(Map.of(
            FunctionalSwarmProtocol.REQUEST_ID_HEADER, "id",
            FunctionalSwarmProtocol.REPLY_LIST_HEADER, "attacker-list"))
        .build();

    assertThatThrownBy(() -> worker.onMessage(input, new TestContext(config)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("configured reply namespace");
    assertThat(transport.responsePayload).isNull();
  }

  private static HttpResultEnvelope httpResult() {
    return HttpResultEnvelope.of(
        new HttpResultEnvelope.HttpRequestInfo("http", "http", "POST", "http://target", "/api", "http://target/api"),
        new HttpResultEnvelope.HttpOutcome("http_response", 200, Map.of("content-type", List.of("text/plain")), "ok", null),
        new HttpResultEnvelope.HttpMetrics(2, 0));
  }

  private record TestContext(FunctionalSwarmReplyWorkerConfig workerConfig) implements WorkerContext {
    @Override public WorkerInfo info() { return INFO; }
    @Override public boolean enabled() { return true; }
    @Override public <C> C config(Class<C> type) { return type.cast(workerConfig); }
    @Override public StatusPublisher statusPublisher() { return StatusPublisher.NO_OP; }
    @Override public org.slf4j.Logger logger() { return LoggerFactory.getLogger("test"); }
    @Override public io.micrometer.core.instrument.MeterRegistry meterRegistry() { return new SimpleMeterRegistry(); }
    @Override public ObservationRegistry observationRegistry() { return ObservationRegistry.create(); }
    @Override public ObservabilityContext observabilityContext() { return new ObservabilityContext(); }
  }

  private static final class CapturingTransport implements FunctionalSwarmRedisTransport {
    private FunctionalSwarmRedisEndpoint endpoint;
    private String replyList;
    private String requestId;
    private String responsePayload;
    private long ttlSeconds;

    @Override public void publishRequest(FunctionalSwarmRedisEndpoint endpoint, String requestList, String requestPayload) {
      throw new AssertionError("reply worker must not publish requests");
    }

    @Override public Optional<String> awaitReply(FunctionalSwarmRedisEndpoint endpoint, String replyList, Duration timeout) {
      throw new AssertionError("reply worker must not await replies");
    }

    @Override
    public boolean publishReplyOnce(
        FunctionalSwarmRedisEndpoint endpoint,
        String replyList,
        String requestId,
        String responsePayload,
        long replyTtlSeconds
    ) {
      this.endpoint = endpoint;
      this.replyList = replyList;
      this.requestId = requestId;
      this.responsePayload = responsePayload;
      this.ttlSeconds = replyTtlSeconds;
      return true;
    }
  }
}
