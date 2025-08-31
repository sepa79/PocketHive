package io.pockethive.postprocessor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.pockethive.Topology;
import io.pockethive.observability.Hop;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.observability.ObservabilityContextUtil;
import org.slf4j.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Component
public class PostProcessor {
  private static final Logger log = LoggerFactory.getLogger(PostProcessor.class);
  private final RabbitTemplate rabbit;
  private final DistributionSummary hopLatency;
  private final DistributionSummary totalLatency;
  private final Counter errorCounter;
  private final String instanceId;

  public PostProcessor(RabbitTemplate rabbit,
                       MeterRegistry registry,
                       @Qualifier("instanceId") String instanceId){
    this.rabbit = rabbit;
    this.instanceId = instanceId;
    this.hopLatency = DistributionSummary.builder("postprocessor_hop_latency_ms").register(registry);
    this.totalLatency = DistributionSummary.builder("postprocessor_total_latency_ms").register(registry);
    this.errorCounter = Counter.builder("postprocessor_errors_total").register(registry);
    try{ sendStatusFull(); } catch(Exception ignore){}
  }

  @RabbitListener(queues = "${ph.finalQueue:ph.final}")
  public void onFinal(byte[] payload,
                      @Header(value="x-ph-trace", required=false) String trace,
                      @Header(value="x-ph-error", required=false) Boolean error){
    long hopMs = 0;
    long totalMs = 0;
    ObservabilityContext ctx = null;
    try {
      ctx = ObservabilityContextUtil.fromHeader(trace);
      ObservabilityContextUtil.populateMdc(ctx);
      if(ctx!=null){
        List<Hop> hops = ctx.getHops();
        if(hops!=null && !hops.isEmpty()){
          Hop last = hops.get(hops.size()-1);
          hopMs = Duration.between(last.getReceivedAt(), last.getProcessedAt()).toMillis();
          Hop first = hops.get(0);
          totalMs = Duration.between(first.getReceivedAt(), last.getProcessedAt()).toMillis();
        }
      }
    } catch(Exception e){
      log.warn("Failed to parse trace header", e);
    } finally {
      MDC.clear();
    }
    hopLatency.record(hopMs);
    totalLatency.record(totalMs);
    boolean isError = Boolean.TRUE.equals(error);
    if(isError) errorCounter.increment();
    sendMetric(hopMs, totalMs, isError);
  }

  private void sendMetric(long hopMs, long totalMs, boolean error){
    String payload = "{"+
      "\"event\":\"metric\","+
      "\"role\":\"postprocessor\","+
      "\"instance\":\""+instanceId+"\","+
      "\"data\":{\"hopLatencyMs\":"+hopMs+",\"totalLatencyMs\":"+totalMs+",\"errors\":"+(error?1:0)+"}}";
    String rk = "ev.metric.postprocessor."+instanceId;
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, rk, payload);
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
          if("status-request".equals(type)) sendStatusFull();
          if("reset".equals(type)){
            // no-op reset placeholder
          }
        }catch(Exception e){ log.warn("control parse", e); }
      }
    } finally {
      MDC.clear();
    }
  }

  private void sendStatusFull(){
    String role = "postprocessor";
    String rk = "ev.status-full."+role+"."+instanceId;
    String payload = envelope(role, new String[]{Topology.FINAL_QUEUE});
    rabbit.convertAndSend(Topology.CONTROL_EXCHANGE, rk, payload);
  }

  private String envelope(String role, String[] inQueues){
    String location = System.getenv().getOrDefault("PH_LOCATION", System.getenv().getOrDefault("HOSTNAME", "local"));
    String messageId = UUID.randomUUID().toString();
    String timestamp = java.time.Instant.now().toString();
    String traffic = Topology.EXCHANGE;
    StringBuilder sb = new StringBuilder(256);
    sb.append('{')
      .append("\"event\":\"status\",")
      .append("\"kind\":\"status.full\",")
      .append("\"version\":\"1.0\",")
      .append("\"role\":\"").append(role).append("\",")
      .append("\"instance\":\"").append(instanceId).append("\",")
      .append("\"location\":\"").append(location).append("\",")
      .append("\"messageId\":\""+messageId+"\",")
      .append("\"timestamp\":\""+timestamp+"\",")
      .append("\"traffic\":\""+traffic+"\"");
    if(inQueues!=null && inQueues.length>0){
      sb.append(',').append("\"queues\":{\"in\":[");
      for(int i=0;i<inQueues.length;i++){ if(i>0) sb.append(','); sb.append('"').append(inQueues[i]).append('"'); }
      sb.append("]}");
    }
    sb.append(',').append("\"data\":{}");
    sb.append('}');
    return sb.toString();
  }
}
