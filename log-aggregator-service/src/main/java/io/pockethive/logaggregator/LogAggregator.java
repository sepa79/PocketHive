package io.pockethive.logaggregator;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

@Component
@EnableScheduling
public class LogAggregator {
  private static final Logger log = LoggerFactory.getLogger(LogAggregator.class);
  private final ObjectMapper mapper = new ObjectMapper();
  private final Queue<LogEntry> buffer = new ConcurrentLinkedQueue<>();
  private final HttpClient http = HttpClient.newHttpClient();
  private final URI lokiUri;
  private final int maxRetries;
  private final long backoffMs;
  private final int batchSize;

  public LogAggregator(
      @Value("${ph.loki.url:http://loki:3100}") String lokiBase,
      @Value("${ph.loki.maxRetries:3}") int maxRetries,
      @Value("${ph.loki.backoffMs:500}") long backoffMs,
      @Value("${ph.loki.batchSize:100}") int batchSize) {
    this.lokiUri = URI.create(lokiBase + "/loki/api/v1/push");
    this.maxRetries = maxRetries;
    this.backoffMs = backoffMs;
    this.batchSize = batchSize;
  }

  @RabbitListener(queues = "${ph.logsQueue:ph.logs.agg}")
  public void onLog(Message message){
    try{
      String json = new String(message.getBody(), StandardCharsets.UTF_8);
      LogEntry entry = mapper.readValue(json, LogEntry.class);
      buffer.add(entry);
    } catch(Exception e){
      log.warn("Failed to decode log message", e);
    }
  }

  @Scheduled(fixedRateString = "${ph.loki.flushIntervalMs:1000}")
  public void flush(){
    List<LogEntry> batch = new ArrayList<>();
    for(int i=0;i<batchSize;i++){
      LogEntry e = buffer.poll();
      if(e==null) break; batch.add(e);
    }
    if(batch.isEmpty()) return;
    Map<String,Object> payload = toPayload(batch);
    send(payload);
  }

  private Map<String,Object> toPayload(List<LogEntry> logs){
    Map<Key, List<List<String>>> grouped = new HashMap<>();
    for(LogEntry l: logs){
      Key key = new Key(empty(l.service()), empty(l.traceId()));
      grouped.computeIfAbsent(key, k -> new ArrayList<>())
          .add(List.of(toNanos(l.timestamp()), l.message()));
    }
    List<Map<String,Object>> streams = new ArrayList<>();
    for(var e: grouped.entrySet()){
      Map<String,String> labels = new HashMap<>();
      if(!e.getKey().service().isBlank()) labels.put("service", e.getKey().service());
      if(!e.getKey().traceId().isBlank()) labels.put("traceId", e.getKey().traceId());
      if(labels.isEmpty()) labels.put("service", "unknown");
      Map<String,Object> stream = new HashMap<>();
      stream.put("stream", labels);
      stream.put("values", e.getValue());
      streams.add(stream);
    }
    return Map.of("streams", streams);
  }

  private String empty(String v){ return v==null?"":v; }

  private String toNanos(String ts){
    Instant i;
    try{ i = (ts==null || ts.isBlank()) ? Instant.now() : Instant.parse(ts); }
    catch(Exception ex){ i = Instant.now(); }
    long nanos = i.getEpochSecond()*1_000_000_000L + i.getNano();
    return Long.toString(nanos);
  }

  private void send(Map<String,Object> payload){
    try{
      String json = mapper.writeValueAsString(payload);
      for(int attempt=1; attempt<=maxRetries; attempt++){
        try{
          HttpRequest req = HttpRequest.newBuilder()
              .uri(lokiUri)
              .header("Content-Type","application/json")
              .POST(HttpRequest.BodyPublishers.ofString(json))
              .build();
          HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
          if(resp.statusCode()/100==2) return;
          throw new RuntimeException("HTTP "+resp.statusCode()+": "+resp.body());
        }catch(Exception e){
          if(attempt==maxRetries){
            log.error("Failed to push logs to Loki", e);
          }else{
            try{ Thread.sleep(backoffMs*attempt); }catch(InterruptedException ie){ Thread.currentThread().interrupt(); }
          }
        }
      }
    }catch(Exception e){
      log.error("Unable to serialize log batch", e);
    }
  }

  private record Key(String service, String traceId){}
}
