package com.example.processor;

import com.example.Topology;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Component
@EnableScheduling
public class Processor {

  private final RabbitTemplate rabbit;
  private final AtomicLong counter = new AtomicLong();
  private final String instanceId = UUID.randomUUID().toString();

  public Processor(RabbitTemplate rabbit){ this.rabbit = rabbit; }

  @RabbitListener(queues = "${ph.modQueue:moderated.queue}")
  public void onModerated(byte[] payload){
    // MVP: pretend to process
    counter.incrementAndGet();
  }

  @Scheduled(fixedRate = 1000)
  public void status(){
    long tps = counter.getAndSet(0);
    String name = "processor";
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
