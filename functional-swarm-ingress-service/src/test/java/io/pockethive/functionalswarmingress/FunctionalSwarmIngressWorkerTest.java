package io.pockethive.functionalswarmingress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.pockethive.functionalswarm.contracts.FunctionalSwarmInvocation;
import io.pockethive.functionalswarm.contracts.FunctionalSwarmJsonCodec;
import io.pockethive.functionalswarm.contracts.FunctionalSwarmProtocol;
import io.pockethive.functionalswarm.contracts.FunctionalSwarmRpcRequest;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.requesttemplates.HttpTemplateReference;
import io.pockethive.requesttemplates.HttpTemplateRenderer;
import io.pockethive.requesttemplates.HttpTemplateResolver;
import io.pockethive.worker.sdk.api.StatusPublisher;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.api.WorkerInfo;
import io.pockethive.templating.PebbleTemplateRenderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class FunctionalSwarmIngressWorkerTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final WorkerInfo INFO = new WorkerInfo("functional-swarm-ingress", "swarm", "instance", null, null);

  @Test
  void validatesRequestAndBuildsTheProcessorHttpEnvelope() throws Exception {
    String requestId = UUID.randomUUID().toString();
    String prefix = "pockethive.functional.reply.";
    FunctionalSwarmIngressWorkerConfig config = new FunctionalSwarmIngressWorkerConfig(
        new HttpTemplateReference(templateRoot().toString(), "functional-swarm-dsl", "invoke"), prefix);
    FunctionalSwarmRpcRequest request = new FunctionalSwarmRpcRequest(
        FunctionalSwarmProtocol.VERSION, requestId, prefix + requestId,
        new FunctionalSwarmInvocation("  body  ", Map.of("x-tenant", "acme")));
    WorkItem input = WorkItem.text(INFO, new FunctionalSwarmJsonCodec().writeRequest(request)).build();
    FunctionalSwarmIngressWorker worker = new FunctionalSwarmIngressWorker(
        new HttpTemplateResolver(), new HttpTemplateRenderer(new PebbleTemplateRenderer()));

    WorkItem result = worker.onMessage(input, new TestContext(config));
    JsonNode envelope = MAPPER.readTree(result.payload());

    assertThat(envelope.path("kind").asText()).isEqualTo("http.request");
    assertThat(envelope.path("request").path("method").asText()).isEqualTo("POST");
    assertThat(envelope.path("request").path("path").asText()).isEqualTo("/api/guarded");
    assertThat(envelope.path("request").path("body").asText()).isEqualTo("  body  ");
    assertThat(result.headers()).containsEntry("x-tenant", "acme")
        .containsEntry(FunctionalSwarmProtocol.REQUEST_ID_HEADER, requestId)
        .containsEntry(FunctionalSwarmProtocol.REPLY_LIST_HEADER, prefix + requestId);
  }

  @Test
  void rejectsAReplyListOutsideItsConfiguredNamespace() throws Exception {
    String requestId = UUID.randomUUID().toString();
    FunctionalSwarmIngressWorkerConfig config = new FunctionalSwarmIngressWorkerConfig(
        new HttpTemplateReference(templateRoot().toString(), "functional-swarm-dsl", "invoke"), "reply.");
    FunctionalSwarmRpcRequest request = new FunctionalSwarmRpcRequest(
        FunctionalSwarmProtocol.VERSION, requestId, "other." + requestId, FunctionalSwarmInvocation.of("body"));
    WorkItem input = WorkItem.text(INFO, new FunctionalSwarmJsonCodec().writeRequest(request)).build();
    FunctionalSwarmIngressWorker worker = new FunctionalSwarmIngressWorker(
        new HttpTemplateResolver(), new HttpTemplateRenderer(new PebbleTemplateRenderer()));

    assertThatThrownBy(() -> worker.onMessage(input, new TestContext(config)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("configured reply namespace");
  }

  private static Path templateRoot() throws Exception {
    Path root = Files.createTempDirectory("functional-swarm-ingress");
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

  private record TestContext(FunctionalSwarmIngressWorkerConfig workerConfig) implements WorkerContext {
    @Override public WorkerInfo info() { return INFO; }
    @Override public boolean enabled() { return true; }
    @Override public <C> C config(Class<C> type) { return type.cast(workerConfig); }
    @Override public StatusPublisher statusPublisher() { return StatusPublisher.NO_OP; }
    @Override public org.slf4j.Logger logger() { return LoggerFactory.getLogger("test"); }
    @Override public io.micrometer.core.instrument.MeterRegistry meterRegistry() { return new SimpleMeterRegistry(); }
    @Override public ObservationRegistry observationRegistry() { return ObservationRegistry.create(); }
    @Override public ObservabilityContext observabilityContext() { return new ObservabilityContext(); }
  }
}
