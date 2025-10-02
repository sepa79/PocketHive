package io.pockethive.postprocessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.pockethive.Topology;
import io.pockethive.control.CommandState;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter.ErrorContext;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter.ReadyContext;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.worker.WorkerConfigCommand;
import io.pockethive.controlplane.worker.WorkerControlPlane;
import io.pockethive.observability.Hop;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.observability.ObservabilityContextUtil;
import io.pockethive.worker.runtime.AbstractWorkerRuntime;
import io.pockethive.worker.runtime.AbstractWorkerRuntime.ListenerLifecycle;
import io.pockethive.worker.runtime.WorkerMessageEnvelope;
import io.pockethive.worker.runtime.WorkerMessageEnvelopeCodec;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
public class PostProcessor extends AbstractWorkerRuntime {

  private static final Logger log = LoggerFactory.getLogger(PostProcessor.class);
  private static final String ROLE = "postprocessor";
  private static final String CONFIG_PHASE = "apply";
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final long STATUS_INTERVAL_MS = 5000L;

  private final RabbitTemplate rabbit;
  private final DistributionSummary hopLatency;
  private final DistributionSummary totalLatency;
  private final DistributionSummary hopCount;
  private final Counter errorCounter;
  private final RabbitListenerEndpointRegistry registry;
  private final ListenerLifecycle workListenerLifecycle;
  private final WorkerMessageEnvelopeCodec envelopeCodec;
  private final AtomicLong counter = new AtomicLong();
  private volatile boolean enabled;
  private volatile long lastStatusTs = System.currentTimeMillis();

  public PostProcessor(RabbitTemplate rabbit,
                       MeterRegistry meterRegistry,
                       @Qualifier("postProcessorControlPlaneEmitter") ControlPlaneEmitter controlEmitter,
                       @Qualifier("workerControlPlaneIdentity") ControlPlaneIdentity identity,
                       @Qualifier("workerControlPlaneTopologyDescriptor") ControlPlaneTopologyDescriptor topology,
                       WorkerControlPlane controlPlane,
                       RabbitListenerEndpointRegistry listenerRegistry) {
    super(log, controlEmitter, controlPlane, identity, topology);
    this.rabbit = Objects.requireNonNull(rabbit, "rabbit");
    this.registry = Objects.requireNonNull(listenerRegistry, "listenerRegistry");
    this.envelopeCodec = new WorkerMessageEnvelopeCodec(MAPPER);
    this.workListenerLifecycle = listenerLifecycle(
        () -> updateListenerState(true),
        () -> updateListenerState(false));
    String instanceId = identity.instanceId();
    String swarmId = identity.swarmId();
    this.hopLatency = DistributionSummary.builder("postprocessor_hop_latency_ms")
        .tag("service", ROLE)
        .tag("instance", instanceId)
        .tag("swarm", swarmId)
        .register(Objects.requireNonNull(meterRegistry, "meterRegistry"));
    this.totalLatency = DistributionSummary.builder("postprocessor_total_latency_ms")
        .tag("service", ROLE)
        .tag("instance", instanceId)
        .tag("swarm", swarmId)
        .register(meterRegistry);
    this.hopCount = DistributionSummary.builder("postprocessor_hops")
        .tag("service", ROLE)
        .tag("instance", instanceId)
        .tag("swarm", swarmId)
        .register(meterRegistry);
    this.errorCounter = Counter.builder("postprocessor_errors_total")
        .tag("service", ROLE)
        .tag("instance", instanceId)
        .tag("swarm", swarmId)
        .register(meterRegistry);
    try {
      sendStatusFull(0);
    } catch (Exception ignore) {
      // best-effort during startup
    }
  }

  @RabbitListener(id = "workListener", queues = "${pockethive.worker.queues.final:${ph.finalQueue:ph.default.final}}")
  public void onFinal(Message message,
                      @Header(value = "x-ph-trace", required = false) String trace,
                      @Header(value = "x-ph-error", required = false) Boolean error) {
    if (!enabled) {
      return;
    }
    WorkerMessageEnvelope envelope = decodeEnvelope(message);
    boolean isError = Boolean.TRUE.equals(error);
    long hopMs = 0;
    long totalMs = 0;
    int hopCnt = 0;
    ObservabilityContext ctx = null;
    try {
      ctx = ObservabilityContextUtil.fromHeader(trace);
      ObservabilityContextUtil.populateMdc(ctx);
      if (ctx != null) {
        List<Hop> hops = ctx.getHops();
        if (hops != null && !hops.isEmpty()) {
          hopCnt = hops.size();
          Hop last = hops.get(hops.size() - 1);
          hopMs = Duration.between(last.getReceivedAt(), last.getProcessedAt()).toMillis();
          Hop first = hops.get(0);
          totalMs = Duration.between(first.getReceivedAt(), last.getProcessedAt()).toMillis();
        }
      }
    } catch (Exception e) {
      log.warn("Failed to parse trace header", e);
    } finally {
      MDC.clear();
    }
    hopLatency.record(hopMs);
    totalLatency.record(totalMs);
    hopCount.record(hopCnt);
    counter.incrementAndGet();
    if (isError) {
      errorCounter.increment();
    }
  }

  @Scheduled(fixedRate = STATUS_INTERVAL_MS)
  public void status() {
    long now = System.currentTimeMillis();
    long elapsed = now - lastStatusTs;
    lastStatusTs = now;
    long tps = elapsed > 0 ? counter.getAndSet(0) * 1000 / elapsed : 0;
    sendStatusDelta(tps);
  }

  @RabbitListener(queues = "#{@postProcessorControlQueueName}")
  public void onControl(String payload,
                        @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String rk,
                        @Header(value = ObservabilityContextUtil.HEADER, required = false) String trace) {
    ObservabilityContext ctx = ObservabilityContextUtil.fromHeader(trace);
    ObservabilityContextUtil.populateMdc(ctx);
    try {
      if (rk == null || rk.isBlank()) {
        log.warn("Received control payload with null or blank routing key; payloadLength={}",
            payload == null ? null : payload.length());
        throw new IllegalArgumentException("Control routing key must not be null or blank");
      }
      if (payload == null || payload.isBlank()) {
        log.warn("Received control payload with null or blank body for routing key {}", rk);
        throw new IllegalArgumentException("Control payload must not be null or blank");
      }
      boolean handled = controlPlane().consume(payload, rk, controlListener());
      if (!handled) {
        log.debug("Ignoring control payload on routing key {}", rk);
      }
    } finally {
      MDC.clear();
    }
  }

  @Override
  protected void handleConfigUpdate(WorkerConfigCommand command) {
    ControlSignal cs = command.signal();
    try {
      applyConfig(command.data());
      emitConfigSuccess(cs);
    } catch (Exception e) {
      log.warn("config update", e);
      emitConfigError(cs, e);
    }
  }

  @Override
  protected ControlPlaneEmitter.StatusContext statusContext(long tps) {
    return baseStatusContext(tps, builder -> builder
        .traffic(Topology.EXCHANGE)
        .workIn(Topology.FINAL_QUEUE)
        .workRoutes(Topology.FINAL_QUEUE)
        .enabled(enabled)
        .data("errors", errorCounter.count()));
  }

  @Override
  protected String statusLogDetails(long tps) {
    return super.statusLogDetails(tps) + " enabled=" + enabled + " errors=" + errorCounter.count();
  }

  private void applyConfig(Map<String, Object> data) {
    if (data == null || data.isEmpty()) {
      return;
    }
    if (data.containsKey("enabled")) {
      boolean newEnabled = parseBoolean(data.get("enabled"));
      boolean changed = newEnabled != enabled;
      enabled = newEnabled;
      if (changed) {
        workListenerLifecycle.apply(enabled);
      }
    }
  }

  private boolean parseBoolean(Object value) {
    if (value == null) {
      return enabled;
    }
    if (value instanceof Boolean b) {
      return b;
    }
    if (value instanceof String s) {
      if (s.isBlank()) {
        return enabled;
      }
      if ("true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s)) {
        return Boolean.parseBoolean(s);
      }
    }
    throw new IllegalArgumentException("Invalid enabled value");
  }

  private void emitConfigSuccess(ControlSignal cs) {
    String signal = requireText(cs.signal(), "signal");
    String correlationId = requireText(cs.correlationId(), "correlationId");
    String idempotencyKey = requireText(cs.idempotencyKey(), "idempotencyKey");
    CommandState state = currentState("completed");
    ReadyContext context = ReadyContext.builder(signal, correlationId, idempotencyKey, state)
        .result("success")
        .build();
    String routingKey = ControlPlaneRouting.event("ready", signal, confirmationScope());
    logControlSend(routingKey, "result=success enabled=" + enabled);
    controlEmitter().emitReady(context);
  }

  private void emitConfigError(ControlSignal cs, Exception e) {
    String signal = requireText(cs.signal(), "signal");
    String correlationId = requireText(cs.correlationId(), "correlationId");
    String idempotencyKey = requireText(cs.idempotencyKey(), "idempotencyKey");
    String code = e.getClass().getSimpleName();
    String message = e.getMessage();
    if (message == null || message.isBlank()) {
      message = code;
    }
    CommandState state = currentState("failed");
    Map<String, Object> details = new LinkedHashMap<>(stateDetails());
    details.put("exception", code);
    ErrorContext context = ErrorContext.builder(signal, correlationId, idempotencyKey, state, CONFIG_PHASE, code, message)
        .retryable(Boolean.FALSE)
        .result("error")
        .details(details)
        .build();
    String routingKey = ControlPlaneRouting.event("error", signal, confirmationScope());
    logControlSend(routingKey, "result=error code=" + code + " enabled=" + enabled);
    controlEmitter().emitError(context);
  }

  private CommandState currentState(String status) {
    return new CommandState(status, enabled, stateDetails());
  }

  private Map<String, Object> stateDetails() {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("errors", errorCounter.count());
    return details;
  }

  private WorkerMessageEnvelope decodeEnvelope(Message message) {
    ObjectNode node = readBody(message);
    MessageProperties properties = message.getMessageProperties();
    ensureMessageId(node, properties);
    ensureTimestamp(node, properties);
    putIfMissing(node, "role", identity().role());
    putIfMissing(node, "instance", identity().instanceId());
    putIfMissing(node, "swarmId", identity().swarmId());
    return envelopeCodec.decode(node);
  }

  private ObjectNode readBody(Message message) {
    try {
      JsonNode node = MAPPER.readTree(message.getBody());
      if (!node.isObject()) {
        throw new IllegalArgumentException("Final payload must be a JSON object");
      }
      return (ObjectNode) node;
    } catch (Exception e) {
      throw new IllegalArgumentException("Unable to parse final payload", e);
    }
  }

  private void ensureMessageId(ObjectNode node, MessageProperties properties) {
    String messageId = textOrNull(node.get("messageId"));
    if (messageId == null || messageId.isBlank()) {
      messageId = properties.getMessageId();
      if (messageId == null || messageId.isBlank()) {
        throw new IllegalArgumentException("messageId must not be null or blank");
      }
      node.put("messageId", messageId);
    }
  }

  private void ensureTimestamp(ObjectNode node, MessageProperties properties) {
    if (node.hasNonNull("timestamp")) {
      return;
    }
    Instant timestamp = properties.getTimestamp() != null
        ? properties.getTimestamp().toInstant()
        : Instant.now();
    node.put("timestamp", timestamp.toString());
  }

  private void putIfMissing(ObjectNode node, String field, String value) {
    if (!node.hasNonNull(field) && value != null) {
      node.put(field, value);
    }
  }

  private String textOrNull(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    String text = node.asText();
    return text != null && text.isBlank() ? null : text;
  }

  private void updateListenerState(boolean start) {
    MessageListenerContainer container = registry.getListenerContainer("workListener");
    if (container == null) {
      return;
    }
    if (start) {
      container.start();
    } else {
      container.stop();
    }
  }
}
