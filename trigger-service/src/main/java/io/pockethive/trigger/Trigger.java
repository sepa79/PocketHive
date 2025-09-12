package io.pockethive.trigger;

import io.pockethive.Topology;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.observability.ObservabilityContextUtil;
import io.pockethive.observability.StatusEnvelopeBuilder;
import org.slf4j.Logger; import org.slf4j.LoggerFactory; import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
@EnableScheduling
public class Trigger {
  private static final Logger log = LoggerFactory.getLogger(Trigger.class);
  private final RabbitTemplate rabbit;
  private final String instanceId;
  private final TriggerConfig config;
  private volatile boolean enabled = false;
  private volatile long lastRun = 0L;
  private static final long STATUS_INTERVAL_MS = 5000L;
  private final AtomicLong counter = new AtomicLong();
  private volatile long lastStatusTs = System.currentTimeMillis();

  public Trigger(RabbitTemplate rabbit,
                 @Qualifier("instanceId") String instanceId,
                 TriggerConfig config) {
    this.rabbit = rabbit;
    this.instanceId = instanceId;
    this.config = config;
    try{ sendStatusFull(0); } catch(Exception ignore){}
  }

  @Scheduled(fixedRate = 1000)
  public void tick(){
    if(!enabled) return;
    long now = System.currentTimeMillis();
    if(now - lastRun >= config.getIntervalMs()){
      lastRun = now;
      triggerOnce();
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
                        @Header(value = ObservabilityContextUtil.HEADER, required = false) String trace){
    ObservabilityContext ctx = ObservabilityContextUtil.fromHeader(trace);
    ObservabilityContextUtil.populateMdc(ctx);
    try{
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
            if(data.has("intervalMs")) config.setIntervalMs(data.get("intervalMs").asLong(config.getIntervalMs()));
            if(data.has("enabled")) enabled = data.get("enabled").asBoolean(enabled);
            if(data.has("singleRequest") && data.get("singleRequest").asBoolean()) triggerOnce();
            if(data.has("actionType")) config.setActionType(data.get("actionType").asText(config.getActionType()));
            if(data.has("command")) config.setCommand(data.get("command").asText(config.getCommand()));
            if(data.has("url")) config.setUrl(data.get("url").asText(config.getUrl()));
            if(data.has("method")) config.setMethod(data.get("method").asText(config.getMethod()));
            if(data.has("body")) config.setBody(data.get("body").asText(config.getBody()));
            if(data.has("headers") && data.get("headers").isObject()){
              Map<String,String> h = new ObjectMapper().convertValue(data.get("headers"), new TypeReference<Map<String,String>>(){});
              config.setHeaders(h);
            }
          }
        }catch(Exception e){ log.warn("control parse", e); }
      }
    } finally {
      MDC.clear();
    }
  }

  private void triggerOnce(){
    counter.incrementAndGet();
    if("shell".equalsIgnoreCase(config.getActionType())){
      runShell();
    }else if("rest".equalsIgnoreCase(config.getActionType())){
      callRest();
    }else{
      log.warn("Unknown actionType: {}", config.getActionType());
    }
  }

  private void runShell(){
    try{
      Process p = new ProcessBuilder("bash", "-c", config.getCommand()).start();
      try(BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))){
        String line; while((line=r.readLine())!=null){ log.info("[SHELL] {}", line); }
      }
      int exit = p.waitFor();
      log.info("[SHELL] exit={} cmd={}", exit, config.getCommand());
    }catch(Exception e){ log.warn("shell exec", e); }
  }

  private void callRest(){
    try{
      HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(config.getUrl()));
      String m = config.getMethod()==null?"GET":config.getMethod().toUpperCase();
      if(config.getBody()!=null && !config.getBody().isEmpty()){
        b.method(m, HttpRequest.BodyPublishers.ofString(config.getBody()));
      }else{
        b.method(m, HttpRequest.BodyPublishers.noBody());
      }
      for(Map.Entry<String,String> e: config.getHeaders().entrySet()){
        b.header(e.getKey(), e.getValue());
      }
      HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.discarding());
      log.info("[REST] {} {}", m, config.getUrl());
    }catch(Exception e){ log.warn("rest call", e); }
  }

  private void sendStatusDelta(long tps){
    String role = "trigger";
    String controlQueue = Topology.CONTROL_QUEUE + "." + role + "." + instanceId;
    String routingKey = "ev.status-delta." + role + "." + instanceId;
    String json = new StatusEnvelopeBuilder()
        .kind("status-delta")
        .role(role)
        .instance(instanceId)
        .traffic(Topology.EXCHANGE)
        .controlIn(controlQueue)
        .controlRoutes(
            "sig.config-update",
            "sig.config-update."+role,
            "sig.config-update."+role+"."+instanceId,
            "sig.status-request",
            "sig.status-request."+role,
            "sig.status-request."+role+"."+instanceId
        )
        .controlOut(routingKey)
        .tps(tps)
        .enabled(enabled)
        .data("intervalMs", config.getIntervalMs())
        .data("actionType", config.getActionType())
        .data("command", config.getCommand())
        .data("url", config.getUrl())
        .data("method", config.getMethod())
        .data("body", config.getBody())
        .data("headers", config.getHeaders())
        .toJson();
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, routingKey, json);
  }

  private void sendStatusFull(long tps){
    String role = "trigger";
    String controlQueue = Topology.CONTROL_QUEUE + "." + role + "." + instanceId;
    String routingKey = "ev.status-full." + role + "." + instanceId;
    String json = new StatusEnvelopeBuilder()
        .kind("status-full")
        .role(role)
        .instance(instanceId)
        .traffic(Topology.EXCHANGE)
        .controlIn(controlQueue)
        .controlRoutes(
            "sig.config-update",
            "sig.config-update."+role,
            "sig.config-update."+role+"."+instanceId,
            "sig.status-request",
            "sig.status-request."+role,
            "sig.status-request."+role+"."+instanceId
        )
        .controlOut(routingKey)
        .tps(tps)
        .enabled(enabled)
        .data("intervalMs", config.getIntervalMs())
        .data("actionType", config.getActionType())
        .data("command", config.getCommand())
        .data("url", config.getUrl())
        .data("method", config.getMethod())
        .data("body", config.getBody())
        .data("headers", config.getHeaders())
        .toJson();
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, routingKey, json);
  }
}
