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
import org.springframework.stereotype.Component;
import org.springframework.amqp.support.AmqpHeaders;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
  private final String instanceId = UUID.randomUUID().toString();
  private volatile boolean enabled = true;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  public PostProcessor(RabbitTemplate rabbit, MeterRegistry registry){
    this.rabbit = rabbit;
    this.hopLatency = DistributionSummary.builder("postprocessor_hop_latency_ms").register(registry);
    this.totalLatency = DistributionSummary.builder("postprocessor_total_latency_ms").register(registry);
    this.errorCounter = Counter.builder("postprocessor_errors_total").register(registry);
  }

  @RabbitListener(queues = "${ph.finalQueue:ph.final}")
  public void onFinal(byte[] payload,
                      @Header(value="x-ph-trace", required=false) String trace,
                      @Header(value="x-ph-error", required=false) Boolean error){
    if(!enabled) return;
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

  @RabbitListener(queues = "${ph.controlQueue:ph.control}")
  public void onControl(String payload,
                        @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String rk,
                        @Header(value = ObservabilityContextUtil.HEADER, required = false) String trace){
    ObservabilityContext ctx = ObservabilityContextUtil.fromHeader(trace);
    ObservabilityContextUtil.populateMdc(ctx);
    try {
      String p = payload==null?"" : (payload.length()>300? payload.substring(0,300)+"…" : payload);
      log.info("[CTRL] RECV rk={} inst={} payload={}", rk, instanceId, p);
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
}
