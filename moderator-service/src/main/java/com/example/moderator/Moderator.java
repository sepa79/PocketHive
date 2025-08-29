package com.example.moderator;

import com.example.Topology;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger; import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.amqp.support.AmqpHeaders;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Component
@EnableScheduling
public class Moderator {

  private static final Logger log = LoggerFactory.getLogger(Moderator.class);
  private final RabbitTemplate rabbit;
  private final AtomicLong counter = new AtomicLong();
  private final String instanceId = UUID.randomUUID().toString();

  public Moderator(RabbitTemplate rabbit) {
    this.rabbit = rabbit;
    try{ sendStatusFull(0); } catch(Exception ignore){}
  }

  // Consume RAW AMQP message to avoid converter issues
  @RabbitListener(queues = "${ph.genQueue:gen.queue}")
  public void onGenerated(Message message) {
    // forward the same message to the moderated queue (preserve body + props)
    rabbit.send(Topology.EXCHANGE, Topology.MOD_QUEUE, message);
    counter.incrementAndGet();
  }

  @Scheduled(fixedRate = 5000)
  public void status() { long tps = counter.getAndSet(0); sendStatusDelta(tps); }

  // Control-plane listener (no-op placeholder)
  @RabbitListener(queues = "${ph.controlQueue:ph.control}")
  public void onControl(String payload, @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String rk) {
    String p = payload==null?"" : (payload.length()>300? payload.substring(0,300)+"…" : payload);
    log.info("[CTRL] RECV rk={} inst={} payload={}", rk, instanceId, p);
    if(payload!=null && payload.contains("status.request")){
      sendStatusFull(0);
    }
  }



  private void sendStatusDelta(long tps){
    String role = "moderator";
    String rk = "ev.status-delta."+role+"."+instanceId;
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, rk, envelope(role, new String[]{Topology.GEN_QUEUE}, new String[]{Topology.MOD_QUEUE}, tps, "status.delta"));
  }
  private void sendStatusFull(long tps){
    String role = "moderator";
    String rk = "ev.status-full."+role+"."+instanceId;
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, rk, envelope(role, new String[]{Topology.GEN_QUEUE}, new String[]{Topology.MOD_QUEUE}, tps, "status.full"));
  }
  private String envelope(String role, String[] inQueues, String[] outQueues, long tps, String kind){
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
      .append("\"messageId\":\""+messageId+"\",")
      .append("\"timestamp\":\""+timestamp+"\",")
      .append("\"traffic\":\""+traffic+"\"");
    if((inQueues!=null && inQueues.length>0) || (outQueues!=null && outQueues.length>0)){
      sb.append(',').append("\"queues\":{");
      if(inQueues!=null && inQueues.length>0){ sb.append("\"in\":["); for(int i=0;i<inQueues.length;i++){ if(i>0) sb.append(','); sb.append('"').append(inQueues[i]).append('"'); } sb.append(']'); }
      if(outQueues!=null && outQueues.length>0){ if(inQueues!=null && inQueues.length>0) sb.append(','); sb.append("\"out\":["); for(int i=0;i<outQueues.length;i++){ if(i>0) sb.append(','); sb.append('"').append(outQueues[i]).append('"'); } sb.append(']'); }
      sb.append('}');
    }
    sb.append(',').append("\"data\":{\"tps\":").append(tps).append('}');
    sb.append('}');
    return sb.toString();
  }
}
