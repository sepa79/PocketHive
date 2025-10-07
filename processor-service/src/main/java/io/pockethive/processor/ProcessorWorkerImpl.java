package io.pockethive.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.pockethive.Topology;
import io.pockethive.TopologyDefaults;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.observability.ObservabilityContextUtil;
import io.pockethive.worker.sdk.api.MessageWorker;
import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.config.PocketHiveWorker;
import io.pockethive.worker.sdk.config.WorkerType;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component("processorWorker")
@PocketHiveWorker(
    role = "processor",
    type = WorkerType.MESSAGE,
  inQueue = TopologyDefaults.MOD_QUEUE,
  outQueue = TopologyDefaults.FINAL_QUEUE,
    config = ProcessorWorkerConfig.class
)
class ProcessorWorkerImpl implements MessageWorker {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final ProcessorDefaults defaults;
  private final HttpClient httpClient;
  private final Clock clock;
  private final AtomicReference<ProcessorMetrics> metricsRef = new AtomicReference<>();

  @Autowired
  ProcessorWorkerImpl(ProcessorDefaults defaults) {
    this(defaults, HttpClient.newHttpClient(), Clock.systemUTC());
  }

  ProcessorWorkerImpl(ProcessorDefaults defaults, HttpClient httpClient, Clock clock) {
    this.defaults = Objects.requireNonNull(defaults, "defaults");
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public WorkResult onMessage(WorkMessage in, WorkerContext context) {
    ProcessorWorkerConfig config = context.config(ProcessorWorkerConfig.class)
        .orElseGet(defaults::asConfig);
    context.statusPublisher()
        .workIn(Topology.MOD_QUEUE)
        .workOut(Topology.FINAL_QUEUE)
        .update(status -> status
            .data("baseUrl", config.baseUrl())
            .data("enabled", config.enabled()));

    ProcessorMetrics metrics = metrics(context);
    Logger logger = context.logger();
    ObservabilityContext observability = context.observabilityContext();
    Instant received = clock.instant();
    long start = System.nanoTime();
    try {
      WorkMessage response = invokeHttp(in, config, logger);
      metrics.recordSuccess(Duration.ofNanos(System.nanoTime() - start));
      WorkMessage enriched = enrich(response, context, observability, received);
      return WorkResult.message(enriched);
    } catch (Exception ex) {
      metrics.recordFailure(Duration.ofNanos(System.nanoTime() - start));
      logger.warn("Processor request failed: {}", ex.toString(), ex);
      WorkMessage error = buildError(ex);
      WorkMessage enriched = enrich(error, context, observability, received);
      return WorkResult.message(enriched);
    }
  }

  private WorkMessage invokeHttp(WorkMessage message, ProcessorWorkerConfig config, Logger logger) throws Exception {
    JsonNode node = message.asJsonNode();
    String baseUrl = config.baseUrl();
    if (baseUrl == null || baseUrl.isBlank()) {
      logger.warn("No baseUrl configured; skipping HTTP call");
      return buildError("invalid baseUrl");
    }

    String path = node.path("path").asText("/");
    String method = node.path("method").asText("GET").toUpperCase();
    URI target = resolveTarget(baseUrl, path);
    if (target == null) {
      logger.warn("Invalid URI base='{}' path='{}'", baseUrl, path);
      return buildError("invalid baseUrl");
    }

    HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(target);
    JsonNode headersNode = node.path("headers");
    if (headersNode.isObject()) {
      headersNode.fields().forEachRemaining(entry -> requestBuilder.header(entry.getKey(), entry.getValue().asText()));
    }

    Optional<String> body = extractBody(node.path("body"));
    HttpRequest.BodyPublisher publisher = body
        .map(value -> HttpRequest.BodyPublishers.ofString(value, StandardCharsets.UTF_8))
        .orElse(HttpRequest.BodyPublishers.noBody());
    requestBuilder.method(method, publisher);

    logger.debug("HTTP {} {} headers={} body={}", method, target, headersNode, body.orElse(""));

  HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
    logger.debug("HTTP {} {} -> {}", method, target, response.statusCode());

    ObjectNode result = MAPPER.createObjectNode();
    result.put("status", response.statusCode());
    result.set("headers", MAPPER.valueToTree(response.headers().map()));
    result.put("body", response.body());

  return WorkMessage.json(result)
    .header("content-type", "application/json")
    .header("x-ph-service", "processor")
    .build();
  }

  private URI resolveTarget(String baseUrl, String path) {
    try {
      return URI.create(baseUrl).resolve(path == null ? "" : path);
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private Optional<String> extractBody(JsonNode bodyNode) throws Exception {
    if (bodyNode == null || bodyNode.isMissingNode() || bodyNode.isNull()) {
      return Optional.empty();
    }
    if (bodyNode.isTextual()) {
      return Optional.of(bodyNode.asText());
    }
    return Optional.of(MAPPER.writeValueAsString(bodyNode));
  }

  private WorkMessage buildError(Exception ex) {
    return buildError(ex.toString());
  }

  private WorkMessage buildError(String message) {
    ObjectNode result = MAPPER.createObjectNode();
    result.put("error", message);
    return WorkMessage.json(result)
        .header("content-type", "application/json")
        .header("x-ph-service", "processor")
        .build();
  }

  private WorkMessage enrich(WorkMessage message,
                             WorkerContext context,
                             ObservabilityContext observability,
                             Instant received) {
    ObservabilityContext targetContext = Objects.requireNonNull(observability, "observability");
    ObservabilityContextUtil.appendHop(targetContext,
        context.info().role(),
        context.info().instanceId(),
        received,
        clock.instant());
    return message.toBuilder()
        .observabilityContext(targetContext)
        .build();
  }

  private ProcessorMetrics metrics(WorkerContext context) {
    ProcessorMetrics current = metricsRef.get();
    if (current != null) {
      return current;
    }
    synchronized (metricsRef) {
      current = metricsRef.get();
      if (current == null) {
        current = new ProcessorMetrics(context.meterRegistry(), context);
        metricsRef.set(current);
      }
      return current;
    }
  }

  private static final class ProcessorMetrics {
    private final DistributionSummary latencySummary;
    private final Counter messageCounter;

  ProcessorMetrics(MeterRegistry registry, WorkerContext context) {
    String role = context.info().role();
    String instance = context.info().instanceId();
    String swarm = context.info().swarmId();
    this.latencySummary = DistributionSummary.builder("processor_request_time_ms")
      .tag("service", role)
      .tag("instance", instance)
      .tag("swarm", swarm)
      .register(registry);
    this.messageCounter = Counter.builder("processor_messages_total")
      .tag("service", role)
      .tag("instance", instance)
      .tag("swarm", swarm)
      .register(registry);
    }

    void recordSuccess(Duration duration) {
      record(duration);
    }

    void recordFailure(Duration duration) {
      record(duration);
    }

    private void record(Duration duration) {
      latencySummary.record(duration.toMillis());
      messageCounter.increment();
    }
  }
}
