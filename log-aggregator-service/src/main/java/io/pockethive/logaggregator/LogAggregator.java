package io.pockethive.logaggregator;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
<<<<<<< ours
<<<<<<< ours
<<<<<<< ours
<<<<<<< ours
<<<<<<< ours
import org.springframework.scheduling.annotation.EnableScheduling;
=======
>>>>>>> theirs
=======
import org.springframework.scheduling.annotation.EnableScheduling;
>>>>>>> theirs
=======
>>>>>>> theirs
=======
>>>>>>> theirs
=======
import org.springframework.scheduling.annotation.EnableScheduling;
>>>>>>> theirs
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

@Component
<<<<<<< ours
<<<<<<< ours
<<<<<<< ours
<<<<<<< ours
<<<<<<< ours
@EnableScheduling
=======
>>>>>>> theirs
=======
@EnableScheduling
>>>>>>> theirs
=======
>>>>>>> theirs
=======
>>>>>>> theirs
=======
@EnableScheduling
>>>>>>> theirs
public class LogAggregator {
  private static final Logger log = LoggerFactory.getLogger(LogAggregator.class);
  private final ObjectMapper mapper = new ObjectMapper();
  private final Queue<LogEntry> buffer = new ConcurrentLinkedQueue<>();
  private final RestTemplate restTemplate;
  private final URI lokiUri;
  private final int maxRetries;
  private final long backoffMs;
  private final int batchSize;

  public LogAggregator(
      @Value("${pockethive.loki.url}") String lokiBase,
      @Value("${pockethive.loki.maxRetries}") int maxRetries,
      @Value("${pockethive.loki.backoffMs}") long backoffMs,
      @Value("${pockethive.loki.batchSize}") int batchSize,
      RestTemplateBuilder restTemplateBuilder) {
    this.lokiUri = URI.create(lokiBase + "/loki/api/v1/push");
    this.maxRetries = maxRetries;
    this.backoffMs = backoffMs;
    this.batchSize = batchSize;
    this.restTemplate = restTemplateBuilder
        .setConnectTimeout(Duration.ofSeconds(5))
        .setReadTimeout(Duration.ofSeconds(10))
        .build();
  }

  @RabbitListener(queues = "${pockethive.logs.queue}")
  public void onLog(Message message){
    try{
      String json = new String(message.getBody(), StandardCharsets.UTF_8);
      LogEntry entry = mapper.readValue(json, LogEntry.class);
      buffer.add(entry);
    } catch(Exception e){
      log.warn("Failed to decode log message", e);
    }
  }

  @Scheduled(fixedRateString = "${pockethive.loki.flushIntervalMs}")
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
      Key key = new Key(
          empty(l.role()),
          empty(l.swarmId()),
          empty(l.instanceId()),
          empty(l.traceId()));
      grouped.computeIfAbsent(key, k -> new ArrayList<>())
          .add(List.of(toNanos(l.timestamp()), l.message()));
    }
    List<Map<String,Object>> streams = new ArrayList<>();
    for(var e: grouped.entrySet()){
      Map<String,String> labels = new HashMap<>();
      if(!e.getKey().role().isBlank()) labels.put("role", e.getKey().role());
      if(!e.getKey().swarmId().isBlank()) labels.put("swarmId", e.getKey().swarmId());
      if(!e.getKey().instanceId().isBlank()) labels.put("instanceId", e.getKey().instanceId());
      if(!e.getKey().traceId().isBlank()) labels.put("traceId", e.getKey().traceId());
      labels.putIfAbsent("role", "unknown");
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
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      HttpEntity<String> entity = new HttpEntity<>(json, headers);

      for(int attempt=1; attempt<=maxRetries; attempt++){
        try{
          ResponseEntity<String> response = restTemplate.postForEntity(lokiUri, entity, String.class);
          if(response.getStatusCode().is2xxSuccessful()) {
            return;
          }
          throw new RestClientException("HTTP " + response.getStatusCodeValue() + ": "
              + Objects.toString(response.getBody(), ""));
        }catch(RestClientException e){
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

  private record Key(String role, String swarmId, String instanceId, String traceId){}
}
