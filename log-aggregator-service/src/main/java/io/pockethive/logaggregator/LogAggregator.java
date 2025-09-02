package io.pockethive.logaggregator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.nio.charset.StandardCharsets;

@Component
@EnableScheduling
public class LogAggregator {
  private static final Logger log = LoggerFactory.getLogger(LogAggregator.class);
  private final ObjectMapper mapper = new ObjectMapper();
  private final Queue<LogEntry> buffer = new ConcurrentLinkedQueue<>();
  private final HttpClient http = HttpClient.newHttpClient();
  private final URI lokiUri;
  private final URI lokiQueryUri;
  private final int maxRetries;
  private final long backoffMs;
  private final int batchSize;
  private final RabbitTemplate rabbit;
  private final String metricsExchange;
  private final String metricsRk;
  private final String grafanaBase;

  public LogAggregator(
      RabbitTemplate rabbit,
      @Value("${ph.loki.url:http://loki:3100}") String lokiBase,
      @Value("${ph.loki.maxRetries:3}") int maxRetries,
      @Value("${ph.loki.backoffMs:500}") long backoffMs,
      @Value("${ph.loki.batchSize:100}") int batchSize,
      @Value("${ph.metrics.exchange:ph.metrics}") String metricsExchange,
      @Value("${ph.metrics.errorRateRk:metrics.error-rate}") String metricsRk,
      @Value("${ph.grafana.url:http://grafana:3000}") String grafanaBase) {
    this.rabbit = rabbit;
    this.lokiUri = URI.create(lokiBase + "/loki/api/v1/push");
    this.lokiQueryUri = URI.create(lokiBase + "/loki/api/v1/query");
    this.maxRetries = maxRetries;
    this.backoffMs = backoffMs;
    this.batchSize = batchSize;
    this.metricsExchange = metricsExchange;
    this.metricsRk = metricsRk;
    this.grafanaBase = grafanaBase.endsWith("/") ? grafanaBase.substring(0, grafanaBase.length()-1) : grafanaBase;
  }

  @RabbitListener(queues = "${ph.logsQueue:logs.agg}")
  public void onLog(byte[] body){
    try{
      LogEntry entry = mapper.readValue(body, LogEntry.class);
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

  @Scheduled(fixedRateString = "${ph.metrics.errorRateIntervalMs:60000}")
  public void errorRate(){
    try{
      Map<String,Map<String,Long>> counts = new HashMap<>();
      Map<String,Long> totals = new HashMap<>();
      for(String w : List.of("1m","5m","15m")){
        JsonNode res = query("sum by (service)(count_over_time({level=\"error\"}["+w+"]))");
        long tot=0;
        for(JsonNode r : res){
          String svc = r.path("metric").path("service").asText();
          long v = r.path("value").path(1).asLong();
          counts.computeIfAbsent(svc,k->new HashMap<>()).put(w,v);
          tot += v;
        }
        totals.put(w, tot);
      }
      Map<String,List<String>> tops = new HashMap<>();
      JsonNode topRes = query("topk(3, count_over_time({level=\"error\"} | regexp \"(?P<exception>[\\w.]+Exception)\"[15m])) by (service,exception)");
      for(JsonNode r : topRes){
        String svc = r.path("metric").path("service").asText();
        String ex = r.path("metric").path("exception").asText();
        tops.computeIfAbsent(svc,k->new ArrayList<>()).add(ex);
      }
      Map<String,Object> comps = new LinkedHashMap<>();
      for(var e : counts.entrySet()){
        String svc = e.getKey();
        Map<String,Long> c = e.getValue();
        long c1 = c.getOrDefault("1m",0L);
        long c5 = c.getOrDefault("5m",0L);
        long c15 = c.getOrDefault("15m",0L);
        Map<String,Object> comp = new LinkedHashMap<>();
        comp.put("counts", Map.of("1m",c1,"5m",c5,"15m",c15));
        comp.put("rates", Map.of("1m",c1/60d,"5m",c5/300d,"15m",c15/900d));
        comp.put("top", tops.getOrDefault(svc, List.of()));
        String state = c1==0?"OK": c1<5?"WARN":"CRIT";
        comp.put("state", state);
        String q = URLEncoder.encode("{service=\""+svc+"\",level=\"error\"}", StandardCharsets.UTF_8);
        comp.put("grafana", grafanaBase + "/explore?query=" + q);
        comps.put(svc, comp);
      }
      Map<String,Object> payload = new LinkedHashMap<>();
      payload.put("ts", Instant.now().toString());
      payload.put("totals", totals);
      payload.put("components", comps);
      String json = mapper.writeValueAsString(payload);
      rabbit.convertAndSend(metricsExchange, metricsRk, json);
    }catch(Exception e){
      log.warn("error-rate", e);
    }
  }

  private JsonNode query(String q) throws Exception {
    String url = lokiQueryUri + "?query=" + URLEncoder.encode(q, StandardCharsets.UTF_8);
    HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
    HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
    if(resp.statusCode()/100!=2) throw new RuntimeException("HTTP "+resp.statusCode()+": "+resp.body());
    JsonNode root = mapper.readTree(resp.body());
    return root.path("data").path("result");
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
      labels.put("service", e.getKey().service());
      labels.put("traceId", e.getKey().traceId());
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
