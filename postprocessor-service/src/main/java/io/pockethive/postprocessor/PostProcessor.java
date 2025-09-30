package io.pockethive.postprocessor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.pockethive.Topology;
import io.pockethive.control.CommandState;
import io.pockethive.control.ControlSignal;
import io.pockethive.control.ConfirmationScope;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.topology.ControlPlaneRouteCatalog;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.ControlQueueDescriptor;
import io.pockethive.controlplane.worker.WorkerConfigCommand;
import io.pockethive.controlplane.worker.WorkerControlPlane;
import io.pockethive.controlplane.worker.WorkerSignalListener;
import io.pockethive.controlplane.worker.WorkerSignalListener.WorkerSignalContext;
import io.pockethive.controlplane.worker.WorkerStatusRequest;
import io.pockethive.observability.Hop;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.observability.ObservabilityContextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Component
@EnableScheduling
public class PostProcessor {
  private static final Logger log = LoggerFactory.getLogger(PostProcessor.class);
  private static final String ROLE = "postprocessor";
  private static final String CONFIG_PHASE = "apply";
  private final RabbitTemplate rabbit;
  private final DistributionSummary hopLatency;
  private final DistributionSummary totalLatency;
  private final DistributionSummary hopCount;
  private final Counter errorCounter;
  private final RabbitListenerEndpointRegistry registry;
  private final ControlPlaneEmitter controlEmitter;
  private final WorkerControlPlane controlPlane;
  private final WorkerSignalListener controlListener;
  private final ControlPlaneIdentity identity;
  private final ConfirmationScope confirmationScope;
  private final String swarmId;
  private final String instanceId;
  private final String controlQueueName;
  private final String[] controlRoutes;
  private volatile boolean enabled = false;
  private final AtomicLong counter = new AtomicLong();
  private static final long STATUS_INTERVAL_MS = 5000L;
  private volatile long lastStatusTs = System.currentTimeMillis();

  public PostProcessor(RabbitTemplate rabbit,
                       MeterRegistry meterRegistry,
                       @Qualifier("postProcessorControlPlaneEmitter") ControlPlaneEmitter controlEmitter,
                       @Qualifier("workerControlPlaneIdentity") ControlPlaneIdentity identity,
                       @Qualifier("workerControlPlaneTopologyDescriptor") ControlPlaneTopologyDescriptor topology,
                       WorkerControlPlane controlPlane,
                       RabbitListenerEndpointRegistry listenerRegistry){
    this.rabbit = Objects.requireNonNull(rabbit, "rabbit");
    this.registry = Objects.requireNonNull(listenerRegistry, "listenerRegistry");
    this.controlEmitter = Objects.requireNonNull(controlEmitter, "controlEmitter");
    this.controlPlane = Objects.requireNonNull(controlPlane, "controlPlane");
    this.identity = Objects.requireNonNull(identity, "identity");
    this.swarmId = identity.swarmId();
    this.instanceId = identity.instanceId();
    this.confirmationScope = new ConfirmationScope(swarmId, identity.role(), instanceId);
    ControlPlaneTopologyDescriptor descriptor = Objects.requireNonNull(topology, "topology");
    this.controlQueueName = descriptor.controlQueue(instanceId)
        .map(ControlQueueDescriptor::name)
        .orElseThrow(() -> new IllegalStateException("Post-processor control queue descriptor is missing"));
    this.controlRoutes = resolveRoutes(descriptor, identity);
    this.hopLatency = DistributionSummary.builder("postprocessor_hop_latency_ms")
        .tag("service", "postprocessor")
        .tag("instance", instanceId)
        .tag("swarm", swarmId)
        .register(Objects.requireNonNull(meterRegistry, "meterRegistry"));
    this.totalLatency = DistributionSummary.builder("postprocessor_total_latency_ms")
        .tag("service", "postprocessor")
        .tag("instance", instanceId)
        .tag("swarm", swarmId)
        .register(meterRegistry);
    this.hopCount = DistributionSummary.builder("postprocessor_hops")
        .tag("service", "postprocessor")
        .tag("instance", instanceId)
        .tag("swarm", swarmId)
        .register(meterRegistry);
    this.errorCounter = Counter.builder("postprocessor_errors_total")
        .tag("service", "postprocessor")
        .tag("instance", instanceId)
        .tag("swarm", swarmId)
        .register(meterRegistry);
    this.controlListener = new WorkerSignalListener() {
      @Override
      public void onStatusRequest(WorkerStatusRequest request) {
        ControlSignal signal = request.signal();
        if (signal.correlationId() != null) {
          MDC.put("correlation_id", signal.correlationId());
        }
        if (signal.idempotencyKey() != null) {
          MDC.put("idempotency_key", signal.idempotencyKey());
        }
        logControlReceive(request.envelope().routingKey(), signal.signal(), request.payload());
        sendStatusFull(0);
      }

      @Override
      public void onConfigUpdate(WorkerConfigCommand command) {
        ControlSignal signal = command.signal();
        if (signal.correlationId() != null) {
          MDC.put("correlation_id", signal.correlationId());
        }
        if (signal.idempotencyKey() != null) {
          MDC.put("idempotency_key", signal.idempotencyKey());
        }
        logControlReceive(command.envelope().routingKey(), signal.signal(), command.payload());
        handleConfigUpdate(command);
      }

      @Override
      public void onUnsupported(WorkerSignalContext context) {
        log.debug("Ignoring unsupported control signal {}", context.envelope().signal().signal());
      }
    };
    try{ sendStatusFull(0); } catch(Exception ignore){}
  }

  @RabbitListener(id = "workListener", queues = "${ph.finalQueue:ph.default.final}")
  public void onFinal(byte[] payload,
                      @Header(value="x-ph-trace", required=false) String trace,
                      @Header(value="x-ph-error", required=false) Boolean error){
    if(!enabled) return;
    long hopMs = 0;
    long totalMs = 0;
    int hopCnt = 0;
    ObservabilityContext ctx = null;
    try {
      ctx = ObservabilityContextUtil.fromHeader(trace);
      ObservabilityContextUtil.populateMdc(ctx);
      if(ctx!=null){
        List<Hop> hops = ctx.getHops();
        if(hops!=null && !hops.isEmpty()){
          hopCnt = hops.size();
          Hop last = hops.get(hops.size()-1);
          hopMs = Duration.between(last.getReceivedAt(), last.getProcessedAt()).toMillis();
          Hop first = hops.get(0);
          totalMs = Duration.between(first.getReceivedAt(), last.getProcessedAt()).toMillis();
        }
      }
    } catch(Exception e){
      log.warn("Failed to parse trace header", e);
    } finally {
      MDC.clear();
    }
    hopLatency.record(hopMs);
    totalLatency.record(totalMs);
    hopCount.record(hopCnt);
    counter.incrementAndGet();
    boolean isError = Boolean.TRUE.equals(error);
    if(isError) errorCounter.increment();
  }

  @Scheduled(fixedRate = STATUS_INTERVAL_MS)
  public void status(){
    long now = System.currentTimeMillis();
    long elapsed = now - lastStatusTs;
    lastStatusTs = now;
    long tps = elapsed > 0 ? counter.getAndSet(0) * 1000 / elapsed : 0;
    sendStatusDelta(tps);
  }

  @RabbitListener(queues = "#{@postProcessorControlQueueName}")
  public void onControl(String payload,
                        @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String rk,
                        @Header(value = ObservabilityContextUtil.HEADER, required = false) String trace){
    ObservabilityContext ctx = ObservabilityContextUtil.fromHeader(trace);
    ObservabilityContextUtil.populateMdc(ctx);
    try{
      if (rk == null || rk.isBlank()) {
        log.warn("Received control payload with null or blank routing key; payloadLength={}", payload == null ? null : payload.length());
        throw new IllegalArgumentException("Control routing key must not be null or blank");
      }
      if (payload == null || payload.isBlank()) {
        log.warn("Received control payload with null or blank body for routing key {}", rk);
        throw new IllegalArgumentException("Control payload must not be null or blank");
      }
      boolean handled = controlPlane.consume(payload, rk, controlListener);
      if (!handled) {
        log.debug("Ignoring control payload on routing key {}", rk);
      }
    } finally {
      MDC.clear();
    }
  }

  private void handleConfigUpdate(WorkerConfigCommand command){
    ControlSignal cs = command.signal();
    try{
      applyConfig(command.data());
      emitConfigSuccess(cs);
    }catch(Exception e){
      log.warn("config update", e);
      emitConfigError(cs, e);
    }
  }

  private void applyConfig(Map<String, Object> data){
    if(data==null || data.isEmpty()){
      return;
    }
    if(data.containsKey("enabled")){
      boolean newEnabled = parseBoolean(data.get("enabled"), enabled);
      if(newEnabled != enabled){
        enabled = newEnabled;
        MessageListenerContainer c = registry.getListenerContainer("workListener");
        if(c != null){
          if(enabled){
            c.start();
          }else{
            c.stop();
          }
        }
      }
    }
  }

  private boolean parseBoolean(Object value, boolean current){
    if(value==null){
      return current;
    }
    if(value instanceof Boolean b){
      return b;
    }
    if(value instanceof String s){
      if(s.isBlank()){
        return current;
      }
      if("true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s)){
        return Boolean.parseBoolean(s);
      }
    }
    throw new IllegalArgumentException("Invalid enabled value");
  }

  private void emitConfigSuccess(ControlSignal cs){
    String signal = requireText(cs.signal(), "signal");
    String correlationId = requireText(cs.correlationId(), "correlationId");
    String idempotencyKey = requireText(cs.idempotencyKey(), "idempotencyKey");
    CommandState state = currentState("completed");
    var context = ControlPlaneEmitter.ReadyContext.builder(signal, correlationId, idempotencyKey, state)
        .result("success")
        .build();
    String routingKey = ControlPlaneRouting.event("ready", signal, confirmationScope);
    logControlSend(routingKey, "result=success enabled=" + enabled);
    controlEmitter.emitReady(context);
  }

  private void emitConfigError(ControlSignal cs, Exception e){
    String signal = requireText(cs.signal(), "signal");
    String correlationId = requireText(cs.correlationId(), "correlationId");
    String idempotencyKey = requireText(cs.idempotencyKey(), "idempotencyKey");
    String code = e.getClass().getSimpleName();
    String message = e.getMessage();
    if(message==null || message.isBlank()){
      message = code;
    }
    CommandState state = currentState("failed");
    Map<String, Object> details = new LinkedHashMap<>(stateDetails());
    details.put("exception", code);
    var context = ControlPlaneEmitter.ErrorContext.builder(signal, correlationId, idempotencyKey, state, CONFIG_PHASE, code, message)
        .retryable(Boolean.FALSE)
        .result("error")
        .details(details)
        .build();
    String routingKey = ControlPlaneRouting.event("error", signal, confirmationScope);
    logControlSend(routingKey, "result=error code=" + code + " enabled=" + enabled);
    controlEmitter.emitError(context);
  }

  private void sendStatusDelta(long tps){
    String routingKey = ControlPlaneRouting.event("status-delta", confirmationScope);
    logControlSend(routingKey, "tps=" + tps + " enabled=" + enabled);
    controlEmitter.emitStatusDelta(statusContext(tps));
  }

  private void sendStatusFull(long tps){
    String routingKey = ControlPlaneRouting.event("status-full", confirmationScope);
    logControlSend(routingKey, "tps=" + tps + " enabled=" + enabled);
    controlEmitter.emitStatusSnapshot(statusContext(tps));
  }

  private void logControlReceive(String routingKey, String signal, String payload) {
    String snippet = snippet(payload);
    if (signal != null && signal.startsWith("status")) {
      log.debug("[CTRL] RECV rk={} inst={} payload={}", routingKey, instanceId, snippet);
    } else if ("config-update".equals(signal)) {
      log.info("[CTRL] RECV rk={} inst={} payload={}", routingKey, instanceId, snippet);
    } else {
      log.info("[CTRL] RECV rk={} inst={} payload={}", routingKey, instanceId, snippet);
    }
  }

  private void logControlSend(String routingKey, String details) {
    String snippet = details == null ? "" : details;
    if (routingKey.contains(".status-")) {
      log.debug("[CTRL] SEND rk={} inst={} {}", routingKey, instanceId, snippet);
    } else if (routingKey.contains(".config-update.")) {
      log.info("[CTRL] SEND rk={} inst={} {}", routingKey, instanceId, snippet);
    } else {
      log.info("[CTRL] SEND rk={} inst={} {}", routingKey, instanceId, snippet);
    }
  }

  private static String snippet(String payload) {
    if (payload == null) {
      return "";
    }
    String trimmed = payload.strip();
    if (trimmed.length() > 300) {
      return trimmed.substring(0, 300) + "…";
    }
    return trimmed;
  }

  private ControlPlaneEmitter.StatusContext statusContext(long tps) {
    return ControlPlaneEmitter.StatusContext.of(builder -> builder
        .traffic(Topology.EXCHANGE)
        .workIn(Topology.FINAL_QUEUE)
        .workRoutes(Topology.FINAL_QUEUE)
        .controlIn(controlQueueName)
        .controlRoutes(controlRoutes)
        .enabled(enabled)
        .tps(tps)
        .data("errors", errorCounter.count()));
  }

  private CommandState currentState(String status) {
    return new CommandState(status, enabled, stateDetails());
  }

  private Map<String, Object> stateDetails() {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("errors", errorCounter.count());
    return details;
  }

  private static String[] resolveRoutes(ControlPlaneTopologyDescriptor descriptor, ControlPlaneIdentity identity) {
    ControlPlaneRouteCatalog routes = descriptor.routes();
    List<String> resolved = new ArrayList<>();
    resolved.addAll(expandRoutes(routes.configSignals(), identity));
    resolved.addAll(expandRoutes(routes.statusSignals(), identity));
    resolved.addAll(expandRoutes(routes.lifecycleSignals(), identity));
    resolved.addAll(expandRoutes(routes.statusEvents(), identity));
    resolved.addAll(expandRoutes(routes.lifecycleEvents(), identity));
    resolved.addAll(expandRoutes(routes.otherEvents(), identity));
    LinkedHashSet<String> unique = resolved.stream()
        .filter(route -> route != null && !route.isBlank())
        .collect(Collectors.toCollection(LinkedHashSet::new));
    return unique.toArray(String[]::new);
  }

  private static List<String> expandRoutes(Set<String> templates, ControlPlaneIdentity identity) {
    if (templates == null || templates.isEmpty()) {
      return List.of();
    }
    return templates.stream()
        .filter(Objects::nonNull)
        .map(route -> route.replace(ControlPlaneRouteCatalog.INSTANCE_TOKEN, identity.instanceId()))
        .toList();
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be null or blank");
    }
    return value;
  }
}
