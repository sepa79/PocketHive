package io.pockethive.processor;

import io.pockethive.Topology;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

@Component
@EnableScheduling
public class Processor {

  private static final Logger log = LoggerFactory.getLogger(Processor.class);
  private final RabbitTemplate rabbit;
  private final AtomicLong counter = new AtomicLong();
  private final String instanceId;
  private volatile boolean enabled = true;
  private volatile int workers = 1;
  private volatile String mode = "simulation";

  public Processor(RabbitTemplate rabbit,
                   @Qualifier("instanceId") String instanceId){
    this.rabbit = rabbit;
    this.instanceId = instanceId;
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
        message.getMessageProperties().setHeader("x-ph-service", "processor");
        message.getMessageProperties().setHeader(ObservabilityContextUtil.HEADER, ObservabilityContextUtil.toHeader(ctx));
        rabbit.send(Topology.EXCHANGE, Topology.FINAL_QUEUE, message);
      }
    } finally {
      MDC.clear();
    }
  }

  @Scheduled(fixedRate = 5000)
  public void status(){ long tps = counter.getAndSet(0); sendStatusDelta(tps); }

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
            if(data.has("workers")) workers = data.get("workers").asInt(workers);
            if(data.has("mode")) mode = data.get("mode").asText(mode);
          }
        }catch(Exception e){ log.warn("control parse", e); }
      }
    } finally {
      MDC.clear();
    }
  }



  private void sendStatusDelta(long tps){
    String role = "processor";
    String rk = "ev.status-delta."+role+"."+instanceId;
    String payload = new StatusEnvelopeBuilder()
        .kind("status-delta")
        .role(role)
        .instance(instanceId)
        .traffic(Topology.EXCHANGE)
        .inQueues(Topology.MOD_QUEUE)
        .outQueues(Topology.FINAL_QUEUE)
        .tps(tps)
        .enabled(enabled)
        .toJson();
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, rk, payload);
  }
  private void sendStatusFull(long tps){
    String role = "processor";
    String rk = "ev.status-full."+role+"."+instanceId;
    String payload = new StatusEnvelopeBuilder()
        .kind("status-full")
        .role(role)
        .instance(instanceId)
        .traffic(Topology.EXCHANGE)
        .inQueues(Topology.MOD_QUEUE)
        .outQueues(Topology.FINAL_QUEUE)
        .tps(tps)
        .enabled(enabled)
        .data("workers", workers)
        .data("mode", mode)
        .toJson();
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, rk, payload);
  }
}
