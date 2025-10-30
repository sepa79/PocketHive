package io.pockethive.postprocessor;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "pockethive.control-plane.worker.postprocessor")
class PostProcessorDefaults {

  private boolean enabled = false;
  private boolean publishAllMetrics = false;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public boolean isPublishAllMetrics() {
    return publishAllMetrics;
  }

  public void setPublishAllMetrics(boolean publishAllMetrics) {
    this.publishAllMetrics = publishAllMetrics;
  }

  PostProcessorWorkerConfig asConfig() {
    return new PostProcessorWorkerConfig(enabled, publishAllMetrics);
  }
}
