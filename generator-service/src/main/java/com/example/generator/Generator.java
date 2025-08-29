package com.example.generator;

import com.example.Topology;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Component
@EnableScheduling
public class Generator {

  private final RabbitTemplate rabbit;
  private final AtomicLong counter = new AtomicLong();
  private final String instanceId = UUID.randomUUID().toString();

  public Generator(RabbitTemplate rabbit) {
    this.rabbit = rabbit;
  }

  @Value("${sim.gen.ratePerSec:5}")
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
          .setHeader("x-sim-service", "generator")
          .build();

      rabbit.convertAndSend(Topology.EXCHANGE, Topology.GEN_QUEUE, msg);
      counter.incrementAndGet();
    }
  }

  @Scheduled(fixedRate = 1000)
  public void status() {
    long tps = counter.getAndSet(0);
    String name = "generator";
    String location = System.getenv().getOrDefault("PH_LOCATION", System.getenv().getOrDefault("HOSTNAME", "local"));
    String messageId = java.util.UUID.randomUUID().toString();
    String timestamp = java.time.Instant.now().toString();
    String traffic = Topology.EXCHANGE;
    String json = "{" +
      "\"name\":\"" + name + "\"," +
      "\"service\":\"" + name + "\"," +
      "\"location\":\"" + location + "\"," +
      "\"instance\":\"" + instanceId + "\"," +
      "\"messageId\":\"" + messageId + "\"," +
      "\"timestamp\":\"" + timestamp + "\"," +
      "\"traffic\":\"" + traffic + "\"," +
      "\"tps\":" + tps +
    "}";
    // Publish to control queue via default exchange (as String)
    rabbit.convertAndSend("", Topology.CONTROL_QUEUE, json);
  }

  // Control-plane listener (no-op placeholder)
  @RabbitListener(queues = "${ph.controlQueue:ph.control}")
  public void onControl(String payload) {
    // Future: handle control messages; for now, ignore or log
  }
}
