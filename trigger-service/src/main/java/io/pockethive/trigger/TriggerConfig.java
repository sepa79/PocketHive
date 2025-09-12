package io.pockethive.trigger;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "ph.trigger")
public class TriggerConfig {
  private long intervalMs = 0L;
  private String actionType = ""; // shell or rest
  private String command = "";
  private String url = "";
  private String method = "";
  private String body = "";
  private Map<String,String> headers = new HashMap<>();

  public long getIntervalMs() { return intervalMs; }
  public void setIntervalMs(long intervalMs) { this.intervalMs = intervalMs; }

  public String getActionType() { return actionType; }
  public void setActionType(String actionType) { this.actionType = actionType; }

  public String getCommand() { return command; }
  public void setCommand(String command) { this.command = command; }

  public String getUrl() { return url; }
  public void setUrl(String url) { this.url = url; }

  public String getMethod() { return method; }
  public void setMethod(String method) { this.method = method; }

  public String getBody() { return body; }
  public void setBody(String body) { this.body = body; }

  public Map<String,String> getHeaders() { return headers; }
  public void setHeaders(Map<String,String> headers) { this.headers = headers; }
}
