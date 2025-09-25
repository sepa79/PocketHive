package io.pockethive.postprocessor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.pockethive.Topology;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.worker.WorkerConfigCommand;
import io.pockethive.controlplane.worker.WorkerControlPlane;
import io.pockethive.controlplane.worker.WorkerSignalListener;
import io.pockethive.controlplane.worker.WorkerSignalListener.WorkerSignalContext;
import io.pockethive.controlplane.worker.WorkerStatusRequest;
import io.pockethive.observability.Hop;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.observability.ObservabilityContextUtil;
import io.pockethive.observability.StatusEnvelopeBuilder;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
@EnableScheduling
public class PostProcessor {
  private static final Logger log = LoggerFactory.getLogger(PostProcessor.class);
  private static final String ROLE = "postprocessor";
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final RabbitTemplate rabbit;
  private final DistributionSummary hopLatency;
  private final DistributionSummary totalLatency;
  private final DistributionSummary hopCount;
  private final Counter errorCounter;
  private final String instanceId;
  private volatile boolean enabled = false;
  private final RabbitListenerEndpointRegistry registry;
  private final AtomicLong counter = new AtomicLong();
  private static final long STATUS_INTERVAL_MS = 5000L;
  private volatile long lastStatusTs = System.currentTimeMillis();
  private final WorkerControlPlane controlPlane;
  private final WorkerSignalListener controlListener;

  public PostProcessor(RabbitTemplate rabbit,
                       MeterRegistry registry,
                       @Qualifier("instanceId") String instanceId,
                       RabbitListenerEndpointRegistry listenerRegistry){
    this.rabbit = rabbit;
    this.instanceId = instanceId;
    this.registry = listenerRegistry;
    this.hopLatency = DistributionSummary.builder("postprocessor_hop_latency_ms")
        .tag("service", "postprocessor")
        .tag("instance", instanceId)
        .tag("swarm", Topology.SWARM_ID)
        .register(registry);
    this.totalLatency = DistributionSummary.builder("postprocessor_total_latency_ms")
        .tag("service", "postprocessor")
        .tag("instance", instanceId)
        .tag("swarm", Topology.SWARM_ID)
        .register(registry);
    this.hopCount = DistributionSummary.builder("postprocessor_hops")
        .tag("service", "postprocessor")
        .tag("instance", instanceId)
        .tag("swarm", Topology.SWARM_ID)
        .register(registry);
    this.errorCounter = Counter.builder("postprocessor_errors_total")
        .tag("service", "postprocessor")
        .tag("instance", instanceId)
        .tag("swarm", Topology.SWARM_ID)
        .register(registry);
    this.controlPlane = WorkerControlPlane.builder(MAPPER)
        .identity(new ControlPlaneIdentity(Topology.SWARM_ID, ROLE, instanceId))
        .build();
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

  @RabbitListener(queues = "#{@controlQueue.name}")
  public void onControl(String payload,
                        @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String rk,
                        @Header(value = ObservabilityContextUtil.HEADER, required = false) String trace){
    ObservabilityContext ctx = ObservabilityContextUtil.fromHeader(trace);
    ObservabilityContextUtil.populateMdc(ctx);
    try{
      if(payload==null || payload.isBlank()){
        return;
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
    String role = resolveRole(cs);
    String instance = resolveInstance(cs);
    String rk = "ev.ready.config-update."+role+"."+instance;
    ObjectNode payload = confirmationPayload(cs, "success", role, instance);
    String json = payload.toString();
    logControlSend(rk, json);
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, rk, json);
  }

  private void emitConfigError(ControlSignal cs, Exception e){
    String role = resolveRole(cs);
    String instance = resolveInstance(cs);
    String rk = "ev.error.config-update."+role+"."+instance;
    ObjectNode payload = confirmationPayload(cs, "error", role, instance);
    payload.put("code", e.getClass().getSimpleName());
    String message = e.getMessage();
    if(message==null || message.isBlank()){
      message = e.getClass().getSimpleName();
    }
    payload.put("message", message);
    String json = payload.toString();
    logControlSend(rk, json);
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, rk, json);
  }

  private ObjectNode confirmationPayload(ControlSignal cs, String result, String role, String instance){
    ObjectNode payload = MAPPER.createObjectNode();
    payload.put("ts", Instant.now().toString());
    payload.put("signal", cs.signal());
    payload.put("result", result);
    payload.set("scope", scopeNode(cs, role, instance));
    payload.set("state", stateNode(cs, role, instance));
    if(cs.correlationId()!=null){
      payload.put("correlationId", cs.correlationId());
    }
    if(cs.idempotencyKey()!=null){
      payload.put("idempotencyKey", cs.idempotencyKey());
    }
    return payload;
  }

  private ObjectNode scopeNode(ControlSignal cs, String role, String instance){
    ObjectNode scope = MAPPER.createObjectNode();
    String swarm = resolveSwarm(cs);
    if(swarm!=null && !swarm.isBlank()){
      scope.put("swarmId", swarm);
    }
    if(role!=null && !role.isBlank()){
      scope.put("role", role);
    }
    if(instance!=null && !instance.isBlank()){
      scope.put("instance", instance);
    }
    return scope;
  }

  private ObjectNode stateNode(ControlSignal cs, String role, String instance){
    ObjectNode state = MAPPER.createObjectNode();
    state.put("enabled", enabled);
    return state;
  }

  private String resolveSwarm(ControlSignal cs){
    if(cs.swarmId()!=null && !cs.swarmId().isBlank()){
      return cs.swarmId();
    }
    return Topology.SWARM_ID;
  }

  private String resolveRole(ControlSignal cs){
    if(cs.role()!=null && !cs.role().isBlank()){
      return cs.role();
    }
    return ROLE;
  }

  private String resolveInstance(ControlSignal cs){
    if(cs.instance()!=null && !cs.instance().isBlank()){
      return cs.instance();
    }
    return instanceId;
  }

  private void sendStatusDelta(long tps){
    String role = ROLE;
    String controlQueue = Topology.CONTROL_QUEUE + "." + role + "." + instanceId;
    String rk = "ev.status-delta."+role+"."+instanceId;
    String payload = new StatusEnvelopeBuilder()
        .kind("status-delta")
        .role(role)
        .instance(instanceId)
        .swarmId(Topology.SWARM_ID)
        .traffic(Topology.EXCHANGE)
        .workIn(Topology.FINAL_QUEUE)
        .controlIn(controlQueue)
        .controlRoutes(
            "sig.config-update",
            "sig.config-update."+role,
            "sig.config-update."+role+"."+instanceId,
            "sig.status-request",
            "sig.status-request."+role,
            "sig.status-request."+role+"."+instanceId
        )
        .controlOut(rk)
        .tps(tps)
        .enabled(enabled)
        .toJson();
    logControlSend(rk, payload);
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, rk, payload);
  }

  private void sendStatusFull(long tps){
    String role = ROLE;
    String controlQueue = Topology.CONTROL_QUEUE + "." + role + "." + instanceId;
    String rk = "ev.status-full."+role+"."+instanceId;
    String payload = new StatusEnvelopeBuilder()
        .kind("status-full")
        .role(role)
        .instance(instanceId)
        .swarmId(Topology.SWARM_ID)
        .traffic(Topology.EXCHANGE)
        .workIn(Topology.FINAL_QUEUE)
        .workRoutes(Topology.FINAL_QUEUE)
        .controlIn(controlQueue)
        .controlRoutes(
            "sig.config-update",
            "sig.config-update."+role,
            "sig.config-update."+role+"."+instanceId,
            "sig.status-request",
            "sig.status-request."+role,
            "sig.status-request."+role+"."+instanceId
        )
        .controlOut(rk)
        .tps(tps)
        .enabled(enabled)
        .toJson();
    logControlSend(rk, payload);
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, rk, payload);
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

  private void logControlSend(String routingKey, String payload) {
    String snippet = snippet(payload);
    if (routingKey.contains(".status-")) {
      log.debug("[CTRL] SEND rk={} inst={} payload={}", routingKey, instanceId, snippet);
    } else if (routingKey.contains(".config-update.")) {
      log.info("[CTRL] SEND rk={} inst={} payload={}", routingKey, instanceId, snippet);
    } else {
      log.info("[CTRL] SEND rk={} inst={} payload={}", routingKey, instanceId, snippet);
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
}
