package io.pockethive.postprocessor;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "pockethive.control-plane.worker.postprocessor")
class PostProcessorDefaults {

  private boolean enabled = false;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  PostProcessorWorkerConfig asConfig() {
    return new PostProcessorWorkerConfig(enabled);
  }
}
