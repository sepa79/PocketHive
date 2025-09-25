package io.pockethive.processor;

import io.pockethive.Topology;
import io.pockethive.control.ControlSignal;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.amqp.support.AmqpHeaders;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.observability.ObservabilityContextUtil;
import io.pockethive.observability.StatusEnvelopeBuilder;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Component
@EnableScheduling
public class Processor {

  private static final Logger log = LoggerFactory.getLogger(Processor.class);
  private static final String ROLE = "processor";
  private final RabbitTemplate rabbit;
  private final Counter messageCounter;
  private double lastCount = 0;
  private final String instanceId;
  private volatile String baseUrl;
  private volatile boolean enabled = false;
  private final RabbitListenerEndpointRegistry registry;
  private final DistributionSummary sutLatency;
  private static final long STATUS_INTERVAL_MS = 5000L;
  private volatile long lastStatusTs = System.currentTimeMillis();
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final HttpClient http = HttpClient.newHttpClient();

  public Processor(RabbitTemplate rabbit,
                   MeterRegistry registry,
                   @Qualifier("instanceId") String instanceId,
                   @Qualifier("baseUrl") String baseUrl,
                   RabbitListenerEndpointRegistry listenerRegistry){
    this.rabbit = rabbit;
    this.instanceId = instanceId;
    this.baseUrl = baseUrl;
    this.registry = listenerRegistry;
    this.sutLatency = DistributionSummary.builder("processor_request_time_ms")
        .tag("service", "processor")
        .tag("instance", instanceId)
        .tag("swarm", Topology.SWARM_ID)
        .register(registry);
    this.messageCounter = Counter.builder("processor_messages_total")
        .tag("service", "processor")
        .tag("instance", instanceId)
        .tag("swarm", Topology.SWARM_ID)
        .register(registry);
    log.info("Base URL: {}", baseUrl);
    try{ sendStatusFull(0); } catch(Exception ignore){}
  }

  @RabbitListener(id = "workListener", queues = "${ph.modQueue:ph.default.mod}")
  public void onModerated(Message message,
                          @Header(value = ObservabilityContextUtil.HEADER, required = false) String trace){
    Instant received = Instant.now();
    ObservabilityContext ctx = ObservabilityContextUtil.fromHeader(trace);
    if(ctx==null){
      ctx = ObservabilityContextUtil.init("processor", instanceId);
      ctx.getHops().clear();
    }
    ObservabilityContextUtil.populateMdc(ctx);
    try {
      if(enabled){
        String raw = new String(message.getBody(), StandardCharsets.UTF_8);
        log.debug("Forwarding to SUT: {}", raw);
        byte[] resp = sendToSut(message.getBody());
        Instant processed = Instant.now();
        ObservabilityContextUtil.appendHop(ctx, "processor", instanceId, received, processed);
        Message out = MessageBuilder
            .withBody(resp)
            .setContentType(MessageProperties.CONTENT_TYPE_JSON)
            .setContentEncoding(StandardCharsets.UTF_8.name())
            .setHeader("x-ph-service", "processor")
            .setHeader(ObservabilityContextUtil.HEADER, ObservabilityContextUtil.toHeader(ctx))
            .build();
        rabbit.send(Topology.EXCHANGE, Topology.FINAL_QUEUE, out);
        messageCounter.increment();
      }
    } finally {
      MDC.clear();
    }
  }

  @Scheduled(fixedRate = STATUS_INTERVAL_MS)
  public void status(){
    long now = System.currentTimeMillis();
    long elapsed = now - lastStatusTs;
    lastStatusTs = now;
    double total = messageCounter.count();
    long tps = 0;
    if(elapsed > 0){
      tps = (long)((total - lastCount) * 1000 / elapsed);
    }
    lastCount = total;
    sendStatusDelta(tps);
  }

  @RabbitListener(queues = "#{@controlQueue.name}")
  public void onControl(String payload,
                        @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String rk,
                        @Header(value = ObservabilityContextUtil.HEADER, required = false) String trace) {
    ObservabilityContext ctx = ObservabilityContextUtil.fromHeader(trace);
    ObservabilityContextUtil.populateMdc(ctx);
    try {
      if(payload==null || payload.isBlank()){
        return;
      }
      ControlSignal cs;
      try {
        cs = MAPPER.readValue(payload, ControlSignal.class);
      } catch (Exception e) {
        log.warn("control parse", e);
        return;
      }
      if (cs.correlationId() != null) {
        MDC.put("correlation_id", cs.correlationId());
      }
      if (cs.idempotencyKey() != null) {
        MDC.put("idempotency_key", cs.idempotencyKey());
      }
      String signal = cs.signal();
      if(signal == null || signal.isBlank()){
        log.warn("control missing signal");
        return;
      }
      logControlReceive(rk, signal, payload);
      switch (signal) {
        case "status-request" -> sendStatusFull(0);
        case "config-update" -> handleConfigUpdate(cs);
        default -> log.debug("Ignoring unsupported control signal {}", signal);
      }
    } finally {
      MDC.clear();
    }
  }

  private void handleConfigUpdate(ControlSignal cs) {
    try {
      Map<String, Object> data = extractConfigData(cs);
      applyConfig(data);
      emitConfigSuccess(cs);
    } catch (Exception e) {
      log.warn("config update", e);
      emitConfigError(cs, e);
    }
  }

  private Map<String, Object> extractConfigData(ControlSignal cs) {
    Map<String, Object> args = cs.args();
    if (args == null || args.isEmpty()) {
      return Map.of();
    }
    Object data = args.containsKey("data") ? args.get("data") : args;
    if (data == null) {
      return Map.of();
    }
    if (data instanceof Map<?, ?> map) {
      Map<String, Object> copy = new LinkedHashMap<>();
      map.forEach((k, v) -> {
        if (k != null) {
          copy.put(k.toString(), v);
        }
      });
      return copy;
    }
    throw new IllegalArgumentException("Invalid config payload");
  }

  private void applyConfig(Map<String, Object> data) {
    if (data == null || data.isEmpty()) {
      return;
    }
    if (data.containsKey("enabled")) {
      boolean newEnabled = parseBoolean(data.get("enabled"), "enabled", enabled);
      if (newEnabled != enabled) {
        enabled = newEnabled;
        MessageListenerContainer c = registry.getListenerContainer("workListener");
        if (c != null) {
          if (enabled) {
            c.start();
          } else {
            c.stop();
          }
        }
      }
    }
    if (data.containsKey("baseUrl")) {
      baseUrl = parseString(data.get("baseUrl"), "baseUrl", baseUrl);
    }
  }

  private boolean parseBoolean(Object value, String field, boolean current) {
    if (value == null) {
      return current;
    }
    if (value instanceof Boolean b) {
      return b;
    }
    if (value instanceof String s) {
      if (s.isBlank()) {
        return current;
      }
      if ("true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s)) {
        return Boolean.parseBoolean(s);
      }
    }
    throw new IllegalArgumentException("Invalid %s value".formatted(field));
  }

  private String parseString(Object value, String field, String current) {
    if (value == null) {
      return current;
    }
    if (value instanceof String s) {
      return s;
    }
    throw new IllegalArgumentException("Invalid %s value type: %s".formatted(field, value.getClass().getSimpleName()));
  }

  private void emitConfigSuccess(ControlSignal cs) {
    String role = resolveRole(cs);
    String instance = resolveInstance(cs);
    String rk = "ev.ready.config-update." + role + "." + instance;
    ObjectNode payload = confirmationPayload(cs, "success", role, instance);
    String json = payload.toString();
    logControlSend(rk, json);
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, rk, json);
  }

  private void emitConfigError(ControlSignal cs, Exception e) {
    String role = resolveRole(cs);
    String instance = resolveInstance(cs);
    String rk = "ev.error.config-update." + role + "." + instance;
    ObjectNode payload = confirmationPayload(cs, "error", role, instance);
    payload.put("code", e.getClass().getSimpleName());
    String message = e.getMessage();
    if (message == null || message.isBlank()) {
      message = e.getClass().getSimpleName();
    }
    payload.put("message", message);
    String json = payload.toString();
    logControlSend(rk, json);
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, rk, json);
  }

  private ObjectNode confirmationPayload(ControlSignal cs, String result, String role, String instance) {
    ObjectNode payload = MAPPER.createObjectNode();
    payload.put("ts", Instant.now().toString());
    payload.put("signal", cs.signal());
    payload.put("result", result);
    payload.set("scope", scopeNode(cs, role, instance));
    payload.set("state", stateNode(cs, role, instance));
    if (cs.correlationId() != null) {
      payload.put("correlationId", cs.correlationId());
    }
    if (cs.idempotencyKey() != null) {
      payload.put("idempotencyKey", cs.idempotencyKey());
    }
    return payload;
  }

  private ObjectNode scopeNode(ControlSignal cs, String role, String instance) {
    ObjectNode scope = MAPPER.createObjectNode();
    String swarm = resolveSwarm(cs);
    if (swarm != null && !swarm.isBlank()) {
      scope.put("swarmId", swarm);
    }
    if (role != null && !role.isBlank()) {
      scope.put("role", role);
    }
    if (instance != null && !instance.isBlank()) {
      scope.put("instance", instance);
    }
    return scope;
  }

  private ObjectNode stateNode(ControlSignal cs, String role, String instance) {
    ObjectNode state = MAPPER.createObjectNode();
    state.set("scope", scopeNode(cs, role, instance));
    state.put("enabled", enabled);
    return state;
  }

  private String resolveSwarm(ControlSignal cs) {
    if (cs.swarmId() != null && !cs.swarmId().isBlank()) {
      return cs.swarmId();
    }
    return Topology.SWARM_ID;
  }

  private String resolveRole(ControlSignal cs) {
    if (cs.role() != null && !cs.role().isBlank()) {
      return cs.role();
    }
    return ROLE;
  }

  private String resolveInstance(ControlSignal cs) {
    if (cs.instance() != null && !cs.instance().isBlank()) {
      return cs.instance();
    }
    return instanceId;
  }

  private byte[] sendToSut(byte[] bodyBytes){
    long start = System.currentTimeMillis();
    String method = "GET";
    URI target = null;
    try{
      JsonNode node = MAPPER.readTree(bodyBytes);
      String path = node.path("path").asText("/");
      method = node.path("method").asText("GET").toUpperCase();

      // Resolve final target URL from configured base and provided path
      target = buildUri(path);
      if(target == null){
        long dur = System.currentTimeMillis() - start;
        sutLatency.record(dur);
        return MAPPER.createObjectNode().put("error", "invalid baseUrl").toString().getBytes(StandardCharsets.UTF_8);
      }

      HttpRequest.Builder req = HttpRequest.newBuilder(target);

      // Copy headers from message
      JsonNode headers = node.path("headers");
      if(headers.isObject()){
        headers.fields().forEachRemaining(e -> req.header(e.getKey(), e.getValue().asText()));
      }

      // Determine body publisher from supplied body node
      JsonNode bodyNode = node.path("body");
      HttpRequest.BodyPublisher bodyPublisher;
      String bodyStr = null;
      if(bodyNode.isMissingNode() || bodyNode.isNull()){
        bodyPublisher = HttpRequest.BodyPublishers.noBody();
      }else if(bodyNode.isTextual()){
        bodyStr = bodyNode.asText();
        bodyPublisher = HttpRequest.BodyPublishers.ofString(bodyStr, StandardCharsets.UTF_8);
      }else{
        bodyStr = MAPPER.writeValueAsString(bodyNode);
        bodyPublisher = HttpRequest.BodyPublishers.ofString(bodyStr, StandardCharsets.UTF_8);
      }

      req.method(method, bodyPublisher);

      String headersStr = headers.isObject()? headers.toString() : "";
      String bodySnippet = bodyStr==null?"": (bodyStr.length()>300? bodyStr.substring(0,300)+"…": bodyStr);
      log.debug("HTTP {} {} headers={} body={}", method, target, headersStr, bodySnippet);

      HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
      long dur = System.currentTimeMillis() - start;
      sutLatency.record(dur);
      log.debug("HTTP {} {} -> {} body={} headers={} ({} ms)", method, target, resp.statusCode(),
          snippet(resp.body()), resp.headers().map(), dur);

      var result = MAPPER.createObjectNode();
      result.put("status", resp.statusCode());
      result.set("headers", MAPPER.valueToTree(resp.headers().map()));
      result.put("body", resp.body());
      return MAPPER.writeValueAsBytes(result);
    }catch(Exception e){
      long dur = System.currentTimeMillis() - start;
      sutLatency.record(dur);
      log.error("HTTP request failed for {} {}: {} ({} ms)", method, target, e.toString(), dur, e);
      return MAPPER.createObjectNode().put("error", e.toString()).toString().getBytes(StandardCharsets.UTF_8);
    }
  }

  private URI buildUri(String path){
    String p = path==null?"":path;
    if(baseUrl == null || baseUrl.isBlank()){
      log.warn("No baseUrl configured, cannot build target URI for path='{}'", p);
      return null;
    }
    try{
      return URI.create(baseUrl).resolve(p);
    }catch(Exception e){
      log.warn("Invalid URI base='{}' path='{}'", baseUrl, p, e);
      return null;
    }
  }

  private static String snippet(String s){
    if(s==null) return "";
    return s.length()>300? s.substring(0,300)+"…" : s;
  }

  private void sendStatusDelta(long tps){
    String role = "processor";
    String controlQueue = Topology.CONTROL_QUEUE + "." + role + "." + instanceId;
    String rk = "ev.status-delta."+role+"."+instanceId;
    String payload = new StatusEnvelopeBuilder()
        .kind("status-delta")
        .role(role)
        .instance(instanceId)
        .swarmId(Topology.SWARM_ID)
        .traffic(Topology.EXCHANGE)
        .workIn(Topology.MOD_QUEUE)
        .workRoutes(Topology.MOD_QUEUE)
        .workOut(Topology.FINAL_QUEUE)
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
        .data("baseUrl", baseUrl)
        .toJson();
    logControlSend(rk, payload);
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, rk, payload);
  }
  private void sendStatusFull(long tps){
    String role = "processor";
    String controlQueue = Topology.CONTROL_QUEUE + "." + role + "." + instanceId;
    String rk = "ev.status-full."+role+"."+instanceId;
    String payload = new StatusEnvelopeBuilder()
        .kind("status-full")
        .role(role)
        .instance(instanceId)
        .swarmId(Topology.SWARM_ID)
        .traffic(Topology.EXCHANGE)
        .workIn(Topology.MOD_QUEUE)
        .workRoutes(Topology.MOD_QUEUE)
        .workOut(Topology.FINAL_QUEUE)
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
        .data("baseUrl", baseUrl)
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
}
