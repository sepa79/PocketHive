package io.pockethive.processor;

import io.pockethive.Topology;
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
import org.slf4j.MDC;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
@EnableScheduling
public class Processor {

  private static final Logger log = LoggerFactory.getLogger(Processor.class);
  private final RabbitTemplate rabbit;
  private final AtomicLong counter = new AtomicLong();
  private final String instanceId = UUID.randomUUID().toString();
  private volatile boolean enabled = true;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  public Processor(RabbitTemplate rabbit){ this.rabbit = rabbit; try{ sendStatusFull(0); } catch(Exception ignore){} }

  @RabbitListener(queues = "${ph.modQueue:moderated.queue}")
  public void onModerated(byte[] payload,
                          @Header(value = "x-ph-service", required = false) String service,
                          @Header(value = ObservabilityContextUtil.HEADER, required = false) String trace){
    ObservabilityContext ctx = ObservabilityContextUtil.fromHeader(trace);
    ObservabilityContextUtil.populateMdc(ctx);
    try {
      if(enabled){
        // MVP: pretend to process
        counter.incrementAndGet();
      }
    } finally {
      MDC.clear();
    }
  }

  @Scheduled(fixedRate = 5000)
  public void status(){ long tps = counter.getAndSet(0); sendStatusDelta(tps); }

  // Control-plane listener (no-op placeholder)
  @RabbitListener(queues = "${ph.controlQueue:ph.control}")
  public void onControl(String payload,
                        @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String rk,
                        @Header(value = ObservabilityContextUtil.HEADER, required = false) String trace) {
    ObservabilityContext ctx = ObservabilityContextUtil.fromHeader(trace);
    ObservabilityContextUtil.populateMdc(ctx);
    try {
      String p = payload==null?"" : (payload.length()>300? payload.substring(0,300)+"…" : payload);
      log.info("[CTRL] RECV rk={} inst={} payload={}", rk, instanceId, p);
      if(payload!=null && payload.contains("status.request")){
        sendStatusFull(0);
      }
      if(payload!=null && payload.contains("config.update")){
        try {
          JsonNode node = MAPPER.readTree(payload);
          JsonNode data = node.get("data");
          if(data!=null && data.has("enabled")){
            enabled = data.get("enabled").asBoolean(true);
            log.info("[CTRL] enabled set to {}", enabled);
          }
        } catch(Exception e){
          log.warn("Config parse error", e);
        }
      }
    } finally {
      MDC.clear();
    }
  }



  private void sendStatusDelta(long tps){
    String role = "processor";
    String rk = "ev.status-delta."+role+"."+instanceId;
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, rk, envelope(role, new String[]{Topology.MOD_QUEUE}, null, tps, "status.delta"));
  }
  private void sendStatusFull(long tps){
    String role = "processor";
    String rk = "ev.status-full."+role+"."+instanceId;
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, rk, envelope(role, new String[]{Topology.MOD_QUEUE}, null, tps, "status.full"));
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
