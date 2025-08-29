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
    String json = "{\"service\":\"processor\",\"instance\":\"" + instanceId + "\",\"tps\":" + tps + "}";
    rabbit.convertAndSend(Topology.STATUS_EXCHANGE, "processor.tps", json);
  }
}
