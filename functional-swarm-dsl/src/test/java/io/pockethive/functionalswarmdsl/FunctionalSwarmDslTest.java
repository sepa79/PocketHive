package io.pockethive.functionalswarmdsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.functionalswarm.contracts.FunctionalSwarmInvocation;
import io.pockethive.functionalswarm.contracts.FunctionalSwarmJsonCodec;
import io.pockethive.functionalswarm.contracts.FunctionalSwarmProtocol;
import io.pockethive.functionalswarm.contracts.FunctionalSwarmRpcRequest;
import io.pockethive.functionalswarm.redis.FunctionalSwarmRedisEndpoint;
import io.pockethive.functionalswarm.redis.FunctionalSwarmRedisTransport;
import io.pockethive.requestexecution.HttpExecutionRequest;
import io.pockethive.requestexecution.HttpExecutionResult;
import io.pockethive.requestexecution.RequestExecutor;
import io.pockethive.requesttemplates.HttpTemplateReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FunctionalSwarmDslTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void localInvokerUsesTheSameBaseUrlAndPathCompositionAsProcessor() throws Exception {
    Path templates = templateRoot();
    CapturingExecutor executor = new CapturingExecutor();
    FunctionalSwarmInvoker invoker = FunctionalSwarmDsl.local(
        new FunctionalSwarmLocalConfig(
            new HttpTemplateReference(templates.toString(), "functional-swarm-dsl", "invoke"),
            java.net.URI.create("http://target.example/base")),
        executor);

    var response = invoker.invoke(new FunctionalSwarmInvocation("  hello  ", Map.of()));

    assertThat(executor.request.method()).isEqualTo("POST");
    assertThat(executor.request.target().toString()).isEqualTo("http://target.example/base/api/guarded");
    assertThat(executor.request.headers()).containsEntry("content-type", "text/plain");
    assertThat(executor.request.body()).isEqualTo("  hello  ");
    assertThat(response.statusCode()).isEqualTo(201);
    assertThat(response.body()).isEqualTo("created");
  }

  @Test
  void remoteInvokerPublishesOnlyTheCanonicalRequestAndMapsItsReply() throws Exception {
    FunctionalSwarmJsonCodec codec = new FunctionalSwarmJsonCodec();
    CapturingTransport transport = new CapturingTransport(httpResultJson(202, "accepted"));
    FunctionalSwarmRemoteConfig config = new FunctionalSwarmRemoteConfig(
        new FunctionalSwarmRedisEndpoint("redis.example", 6380, true, 2_000),
        "pockethive.functional.requests",
        "pockethive.functional.reply.",
        Duration.ofSeconds(3));
    FunctionalSwarmInvoker invoker = new RedisFunctionalSwarmInvoker(config, codec, transport);

    var response = invoker.invoke(new FunctionalSwarmInvocation("body", Map.of("x-tenant", "acme")));

    FunctionalSwarmRpcRequest published = codec.readRequest(transport.requestPayload);
    assertThat(transport.requestList).isEqualTo(config.requestList());
    assertThat(published.protocolVersion()).isEqualTo(FunctionalSwarmProtocol.VERSION);
    assertThat(published.replyList()).isEqualTo(config.replyListPrefix() + published.requestId());
    assertThat(published.invocation().headers()).containsEntry("x-tenant", "acme");
    assertThat(transport.endpoint).isEqualTo(config.redis());
    assertThat(transport.awaitedList).isEqualTo(published.replyList());
    assertThat(response.statusCode()).isEqualTo(202);
    assertThat(response.body()).isEqualTo("accepted");
  }

  @Test
  void remoteInvokerFailsExplicitlyWhenNoReplyArrives() {
    FunctionalSwarmRedisTransport noReply = new CapturingTransport(null);
    FunctionalSwarmRemoteConfig config = new FunctionalSwarmRemoteConfig(
        new FunctionalSwarmRedisEndpoint("redis", 6379, false, 1_000),
        "requests", "replies.", Duration.ofMillis(50));
    FunctionalSwarmInvoker invoker = new RedisFunctionalSwarmInvoker(
        config, new FunctionalSwarmJsonCodec(), noReply);

    assertThatThrownBy(() -> invoker.invoke(FunctionalSwarmInvocation.of("body")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Timed out waiting for Functional Swarm response");
  }

  private static Path templateRoot() throws Exception {
    Path root = Files.createTempDirectory("functional-swarm-templates");
    Path service = Files.createDirectories(root.resolve("functional-swarm-dsl"));
    Files.writeString(service.resolve("invoke.yaml"), """
        serviceId: functional-swarm-dsl
        callId: invoke
        protocol: HTTP
        method: POST
        pathTemplate: /api/guarded
        headersTemplate:
          content-type: text/plain
        bodyTemplate: "{{ payload }}"
        """);
    return root;
  }

  private static String httpResultJson(int status, String body) throws Exception {
    return MAPPER.writeValueAsString(Map.of(
        "kind", "http.result",
        "request", Map.of(
            "transport", "http", "scheme", "http", "method", "POST", "baseUrl", "http://target.example",
            "path", "/api/guarded", "url", "http://target.example/api/guarded"),
        "outcome", Map.of("type", "http_response", "status", status, "headers", Map.of("x-result", List.of("ok")), "body", body),
        "metrics", Map.of("durationMs", 1, "connectionLatencyMs", 0)));
  }

  private static final class CapturingExecutor implements RequestExecutor {
    private HttpExecutionRequest request;

    @Override
    public HttpExecutionResult execute(HttpExecutionRequest request) {
      this.request = request;
      return new HttpExecutionResult(201, Map.of("x-result", List.of("created")), "created");
    }
  }

  private static final class CapturingTransport implements FunctionalSwarmRedisTransport {
    private final String reply;
    private FunctionalSwarmRedisEndpoint endpoint;
    private String requestList;
    private String requestPayload;
    private String awaitedList;

    private CapturingTransport(String reply) {
      this.reply = reply;
    }

    @Override
    public void publishRequest(FunctionalSwarmRedisEndpoint endpoint, String requestList, String requestPayload) {
      this.endpoint = endpoint;
      this.requestList = requestList;
      this.requestPayload = requestPayload;
    }

    @Override
    public Optional<String> awaitReply(FunctionalSwarmRedisEndpoint endpoint, String replyList, Duration timeout) {
      this.awaitedList = replyList;
      return Optional.ofNullable(reply);
    }

    @Override
    public boolean publishReplyOnce(
        FunctionalSwarmRedisEndpoint endpoint,
        String replyList,
        String requestId,
        String responsePayload,
        long replyTtlSeconds
    ) {
      throw new AssertionError("remote client must not publish replies");
    }
  }
}
