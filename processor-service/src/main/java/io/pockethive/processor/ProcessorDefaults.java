package io.pockethive.processor;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "pockethive.control-plane.worker.processor")
class ProcessorDefaults {

  private boolean enabled = false;
  private String baseUrl = "";

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(String baseUrl) {
    this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
  }

  ProcessorWorkerConfig asConfig() {
    return new ProcessorWorkerConfig(enabled, baseUrl);
  }
}
