package io.pockethive.postprocessor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class PostProcessorDefaults {

  private final boolean defaultEnabled;

  PostProcessorDefaults(@Value("${ph.postprocessor.enabled:false}") boolean defaultEnabled) {
    this.defaultEnabled = defaultEnabled;
  }

  PostProcessorWorkerConfig asConfig() {
    return new PostProcessorWorkerConfig(defaultEnabled);
  }
}
