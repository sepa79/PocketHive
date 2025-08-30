package io.pockethive.generator;

import io.pockethive.Topology;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger; import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.amqp.support.AmqpHeaders;

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
  private final String instanceId = UUID.randomUUID().toString();

  public Generator(RabbitTemplate rabbit) {
    this.rabbit = rabbit;
    // Emit full snapshot on startup
    try{ sendStatusFull(0); } catch(Exception ignore){}
  }

  @Value("${ph.gen.ratePerSec:5}")
  private int ratePerSec;

  @Scheduled(fixedRate = 1000)
  public void tick() {
    for (int i = 0; i < ratePerSec; i++) {
      String id = UUID.randomUUID().toString();

      String body = """
        {
          "id":"%s",
          "path":"/api/test",
          "method":"POST",
          "body":"hello-world",
          "createdAt":"%s"
        }
        """.formatted(id, Instant.now().toString());

      Message msg = MessageBuilder
          .withBody(body.getBytes(StandardCharsets.UTF_8))
          .setContentType(MessageProperties.CONTENT_TYPE_JSON) // application/json
          .setContentEncoding(StandardCharsets.UTF_8.name())
          .setMessageId(id)
          .setHeader("x-ph-service", "generator")
          .build();

      rabbit.convertAndSend(Topology.EXCHANGE, Topology.GEN_QUEUE, msg);
      counter.incrementAndGet();
    }
  }

  @Scheduled(fixedRate = 5000)
  public void status() {
    long tps = counter.getAndSet(0);
    sendStatusDelta(tps);
  }

  // Control-plane listener (no-op placeholder)
  @RabbitListener(queues = "${ph.controlQueue:ph.control}")
  public void onControl(String payload, @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String rk) {
    // Log control event
    String p = payload==null?"" : (payload.length()>300? payload.substring(0,300)+"…" : payload);
    log.info("[CTRL] RECV rk={} inst={} payload={}", rk, instanceId, p);
    // Respond to status.request by emitting full snapshot
    if(payload!=null && payload.contains("status.request")){
      sendStatusFull(0);
    }
  }



  private void sendStatusDelta(long tps){
    String role = "generator";
    String routingKey = "ev.status-delta." + role + "." + instanceId;
    String json = envelope(role, null, tps, "status.delta");
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, routingKey, json);
  }

  private void sendStatusFull(long tps){
    String role = "generator";
    String routingKey = "ev.status-full." + role + "." + instanceId;
    String json = envelope(role, new String[]{Topology.GEN_QUEUE}, tps, "status.full");
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, routingKey, json);
  }

  private String envelope(String role, String[] outQueues, long tps, String kind){
    String location = System.getenv().getOrDefault("PH_LOCATION", System.getenv().getOrDefault("HOSTNAME", "local"));
    String messageId = java.util.UUID.randomUUID().toString();
    String timestamp = java.time.Instant.now().toString();
    String traffic = Topology.EXCHANGE;
    StringBuilder sb = new StringBuilder(256);
    sb.append('{')
      .append("\"event\":\"status\",")
      .append("\"kind\":\"").append(kind).append("\",")
      .append("\"version\":\"1.0\",")
      .append("\"role\":\"").append(role).append("\",")
      .append("\"instance\":\"").append(instanceId).append("\",")
      .append("\"location\":\"").append(location).append("\",")
      .append("\"messageId\":\"").append(messageId).append("\",")
      .append("\"timestamp\":\"").append(timestamp).append("\",")
      .append("\"traffic\":\"").append(traffic).append("\"");
    if(outQueues!=null && outQueues.length>0){
      sb.append(',').append("\"queues\":{\"out\":[");
      for(int i=0;i<outQueues.length;i++){ if(i>0) sb.append(','); sb.append('"').append(outQueues[i]).append('"'); }
      sb.append("]}");
    }
    sb.append(',').append("\"data\":{\"tps\":").append(tps).append('}');
    sb.append('}');
    return sb.toString();
  }
}
