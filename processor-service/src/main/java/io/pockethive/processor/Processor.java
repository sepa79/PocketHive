package io.pockethive.processor;

import io.pockethive.Topology;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
@EnableScheduling
public class Processor {

  private static final Logger log = LoggerFactory.getLogger(Processor.class);
  private final RabbitTemplate rabbit;
  private final AtomicLong counter = new AtomicLong();
  private final String instanceId;
  private volatile String baseUrl;
  private volatile boolean enabled = true;
  private static final long STATUS_INTERVAL_MS = 5000L;
  private volatile long lastStatusTs = System.currentTimeMillis();
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final HttpClient http = HttpClient.newHttpClient();

  public Processor(RabbitTemplate rabbit,
                   @Qualifier("instanceId") String instanceId,
                   @Qualifier("baseUrl") String baseUrl){
    this.rabbit = rabbit;
    this.instanceId = instanceId;
    this.baseUrl = baseUrl;
    log.info("Base URL: {}", baseUrl);
    try{ sendStatusFull(0); } catch(Exception ignore){}
  }

  @RabbitListener(queues = "${ph.modQueue:moderated.queue}")
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
        counter.incrementAndGet();
        Instant processed = Instant.now();
        ObservabilityContextUtil.appendHop(ctx, "processor", instanceId, received, processed);
        String raw = new String(message.getBody(), StandardCharsets.UTF_8);
        log.info("Forwarding to SUT: {}", raw);
        byte[] resp = sendToSut(message.getBody());
        Message out = MessageBuilder
            .withBody(resp)
            .setContentType(MessageProperties.CONTENT_TYPE_JSON)
            .setContentEncoding(StandardCharsets.UTF_8.name())
            .setHeader("x-ph-service", "processor")
            .setHeader(ObservabilityContextUtil.HEADER, ObservabilityContextUtil.toHeader(ctx))
            .build();
        rabbit.send(Topology.EXCHANGE, Topology.FINAL_QUEUE, out);
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
    long tps = elapsed > 0 ? counter.getAndSet(0) * 1000 / elapsed : 0;
    sendStatusDelta(tps);
  }

  @RabbitListener(queues = "#{@controlQueue.name}")
  public void onControl(String payload,
                        @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String rk,
                        @Header(value = ObservabilityContextUtil.HEADER, required = false) String trace) {
    ObservabilityContext ctx = ObservabilityContextUtil.fromHeader(trace);
    ObservabilityContextUtil.populateMdc(ctx);
    try {
      String p = payload==null?"" : (payload.length()>300? payload.substring(0,300)+"…" : payload);
      log.info("[CTRL] RECV rk={} inst={} payload={}", rk, instanceId, p);
      if(payload!=null){
        try{
          com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);
          String type = node.path("type").asText();
          if("status-request".equals(type)){
            sendStatusFull(0);
          }
          if("config-update".equals(type)){
            com.fasterxml.jackson.databind.JsonNode data = node.path("data");
            if(data.has("enabled")) enabled = data.get("enabled").asBoolean(enabled);
            if(data.has("baseUrl")) baseUrl = data.get("baseUrl").asText(baseUrl);
          }
        }catch(Exception e){ log.warn("control parse", e); }
      }
    } finally {
      MDC.clear();
    }
  }
  private byte[] sendToSut(byte[] bodyBytes){
    String method = "GET";
    URI target = null;
    try{
      JsonNode node = MAPPER.readTree(bodyBytes);
      String path = node.path("path").asText("/");
      method = node.path("method").asText("GET").toUpperCase();

      // Resolve final target URL from configured base and provided path
      target = buildUri(path);
      if(target == null){
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
      log.info("HTTP {} {} headers={} body={}", method, target, headersStr, bodySnippet);

      HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
      log.info("HTTP {} {} -> {} body={} headers={}", method, target, resp.statusCode(),
          snippet(resp.body()), resp.headers().map());

      var result = MAPPER.createObjectNode();
      result.put("status", resp.statusCode());
      result.set("headers", MAPPER.valueToTree(resp.headers().map()));
      result.put("body", resp.body());
      return MAPPER.writeValueAsBytes(result);
    }catch(Exception e){
      log.error("HTTP request failed for {} {}: {}", method, target, e.toString(), e);
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
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, rk, payload);
  }
}
