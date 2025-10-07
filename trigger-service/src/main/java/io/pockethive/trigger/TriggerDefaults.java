package io.pockethive.trigger;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ph.trigger")
class TriggerDefaults {

  private boolean enabled = false;
  private long intervalMs = 0L;
  private String actionType = "";
  private String command = "";
  private String url = "";
  private String method = "";
  private String body = "";
  private Map<String, String> headers = new HashMap<>();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public long getIntervalMs() {
    return intervalMs;
  }

  public void setIntervalMs(long intervalMs) {
    this.intervalMs = intervalMs;
  }

  public String getActionType() {
    return actionType;
  }

  public void setActionType(String actionType) {
    this.actionType = actionType;
  }

  public String getCommand() {
    return command;
  }

  public void setCommand(String command) {
    this.command = command;
  }

  public String getUrl() {
    return url;
  }

  public void setUrl(String url) {
    this.url = url;
  }

  public String getMethod() {
    return method;
  }

  public void setMethod(String method) {
    this.method = method;
  }

  public String getBody() {
    return body;
  }

  public void setBody(String body) {
    this.body = body;
  }

  public Map<String, String> getHeaders() {
    return headers;
  }

  public void setHeaders(Map<String, String> headers) {
    this.headers = headers;
  }

  TriggerWorkerConfig asConfig() {
    return new TriggerWorkerConfig(
        enabled,
        intervalMs,
        false,
        actionType,
        command,
        url,
        method,
        body,
        headers
    );
  }
}
