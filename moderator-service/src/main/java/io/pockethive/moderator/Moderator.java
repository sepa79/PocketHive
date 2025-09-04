package io.pockethive.moderator;

import io.pockethive.Topology;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger; import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.amqp.support.AmqpHeaders;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.observability.ObservabilityContextUtil;
import io.pockethive.observability.StatusEnvelopeBuilder;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.concurrent.atomic.AtomicLong;
import java.time.Instant;

@Component
@EnableScheduling
public class Moderator {

  private static final Logger log = LoggerFactory.getLogger(Moderator.class);
  private final RabbitTemplate rabbit;
  private final AtomicLong counter = new AtomicLong();
  private final String instanceId;
  private final RabbitListenerEndpointRegistry registry;
  private volatile boolean enabled = true;
  private static final long STATUS_INTERVAL_MS = 5000L;
  private volatile long lastStatusTs = System.currentTimeMillis();

  public Moderator(RabbitTemplate rabbit,
                   @Qualifier("instanceId") String instanceId,
                   RabbitListenerEndpointRegistry registry) {
    this.rabbit = rabbit;
    this.instanceId = instanceId;
    this.registry = registry;
    try{ sendStatusFull(0); } catch(Exception ignore){}
  }

  // Consume RAW AMQP message to avoid converter issues
  @RabbitListener(id = "workListener", queues = "${ph.genQueue:gen.queue}")
  public void onGenerated(Message message,
                          @Header(value = "x-ph-service", required = false) String service,
                          @Header(value = ObservabilityContextUtil.HEADER, required = false) String trace) {
    Instant received = Instant.now();
    ObservabilityContext ctx = ObservabilityContextUtil.fromHeader(trace);
    if(ctx==null){
      ctx = ObservabilityContextUtil.init("moderator", instanceId);
      ctx.getHops().clear();
    }
    ObservabilityContextUtil.populateMdc(ctx);
    try {
      if(enabled){
        counter.incrementAndGet();
        Instant processed = Instant.now();
        ObservabilityContextUtil.appendHop(ctx, "moderator", instanceId, received, processed);
        Message out = MessageBuilder.fromMessage(message)
            .setHeader("x-ph-service", "moderator")
            .setHeader(ObservabilityContextUtil.HEADER, ObservabilityContextUtil.toHeader(ctx))
            .build();
        rabbit.send(Topology.EXCHANGE, Topology.MOD_QUEUE, out);
      }
    } finally {
      MDC.clear();
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
            if(data.has("enabled")){
              boolean newEnabled = data.get("enabled").asBoolean(enabled);
              if(newEnabled != enabled){
                enabled = newEnabled;
                MessageListenerContainer c = registry.getListenerContainer("workListener");
                if(c != null){
                  if(enabled) c.start(); else c.stop();
                }
              }
            }
          }
        }catch(Exception e){ log.warn("control parse", e); }
      }
    } finally {
      MDC.clear();
    }
  }



  private void sendStatusDelta(long tps){
    String role = "moderator";
    String controlQueue = Topology.CONTROL_QUEUE + "." + role + "." + instanceId;
    String rk = "ev.status-delta."+role+"."+instanceId;
    String payload = new StatusEnvelopeBuilder()
        .kind("status-delta")
        .role(role)
        .instance(instanceId)
        .traffic(Topology.EXCHANGE)
        .workIn(Topology.GEN_QUEUE)
        .workRoutes(Topology.GEN_QUEUE)
        .workOut(Topology.MOD_QUEUE)
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
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, rk, payload);
  }
  private void sendStatusFull(long tps){
    String role = "moderator";
    String controlQueue = Topology.CONTROL_QUEUE + "." + role + "." + instanceId;
    String rk = "ev.status-full."+role+"."+instanceId;
    String payload = new StatusEnvelopeBuilder()
        .kind("status-full")
        .role(role)
        .instance(instanceId)
        .traffic(Topology.EXCHANGE)
        .workIn(Topology.GEN_QUEUE)
        .workRoutes(Topology.GEN_QUEUE)
        .workOut(Topology.MOD_QUEUE)
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
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, rk, payload);
  }
}
