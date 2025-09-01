package io.pockethive.generator;

import io.pockethive.Topology;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger; import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.amqp.support.AmqpHeaders;
import org.slf4j.MDC;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.observability.ObservabilityContextUtil;
import io.pockethive.observability.StatusEnvelopeBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Component
@EnableScheduling
public class Generator {

  private static final Logger log = LoggerFactory.getLogger(Generator.class);
  private final RabbitTemplate rabbit;
  private final AtomicLong counter = new AtomicLong();
  private final String instanceId;
  private volatile boolean enabled = true;

  public Generator(RabbitTemplate rabbit,
                   @Qualifier("instanceId") String instanceId) {
    this.rabbit = rabbit;
    this.instanceId = instanceId;
    // Emit full snapshot on startup
    try{ sendStatusFull(0); } catch(Exception ignore){}
  }

  @Value("${ph.gen.ratePerSec:5}")
  private int ratePerSec;

  @Scheduled(fixedRate = 1000)
  public void tick() {
    if(!enabled) return;
    for (int i = 0; i < ratePerSec; i++) {
      sendOnce();
    }
  }

  @Scheduled(fixedRate = 5000)
  public void status() {
    long tps = counter.getAndSet(0);
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
            if(data.has("ratePerSec")) ratePerSec = data.get("ratePerSec").asInt(ratePerSec);
            if(data.has("enabled")) enabled = data.get("enabled").asBoolean(enabled);
            if(data.has("singleRequest") && data.get("singleRequest").asBoolean()) sendOnce();
          }
        }catch(Exception e){ log.warn("control parse", e); }
      }
    } finally {
      MDC.clear();
    }
  }

  private void sendOnce(){
    String id = UUID.randomUUID().toString();
    String body = """
      {
        \"id\":\"%s\",
        \"path\":\"/api/test\",
        \"method\":\"POST\",
        \"body\":\"hello-world\",
        \"createdAt\":\"%s\"
      }
      """.formatted(id, Instant.now().toString());
    Message msg = MessageBuilder
        .withBody(body.getBytes(StandardCharsets.UTF_8))
        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
        .setContentEncoding(StandardCharsets.UTF_8.name())
        .setMessageId(id)
        .setHeader("x-ph-service", "generator")
        .build();
    rabbit.convertAndSend(Topology.EXCHANGE, Topology.GEN_QUEUE, msg);
    counter.incrementAndGet();
  }



  private void sendStatusDelta(long tps){
    String role = "generator";
    String controlQueue = Topology.CONTROL_QUEUE + "." + role + "." + instanceId;
    String routingKey = "ev.status-delta." + role + "." + instanceId;
    String json = new StatusEnvelopeBuilder()
        .kind("status-delta")
        .role(role)
        .instance(instanceId)
        .traffic(Topology.EXCHANGE)
        .inQueue(controlQueue, "sig.#", "sig.#."+role, "sig.#."+role+"."+instanceId)
        .publishes(Topology.GEN_QUEUE)
        .tps(tps)
        .enabled(enabled)
        .data("ratePerSec", ratePerSec)
        .toJson();
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, routingKey, json);
  }

  private void sendStatusFull(long tps){
    String role = "generator";
    String controlQueue = Topology.CONTROL_QUEUE + "." + role + "." + instanceId;
    String routingKey = "ev.status-full." + role + "." + instanceId;
    String json = new StatusEnvelopeBuilder()
        .kind("status-full")
        .role(role)
        .instance(instanceId)
        .traffic(Topology.EXCHANGE)
        .inQueue(controlQueue, "sig.#", "sig.#."+role, "sig.#."+role+"."+instanceId)
        .publishes(Topology.GEN_QUEUE)
        .tps(tps)
        .enabled(enabled)
        .data("ratePerSec", ratePerSec)
        .toJson();
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, routingKey, json);
  }
}
