package io.pockethive.processor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class ProcessorDefaults {

  private final boolean defaultEnabled;
  private final String defaultBaseUrl;

  ProcessorDefaults(@Value("${ph.processor.enabled:false}") boolean defaultEnabled,
                    @Value("${ph.processor.baseUrl:}") String defaultBaseUrl) {
    this.defaultEnabled = defaultEnabled;
    this.defaultBaseUrl = defaultBaseUrl == null ? "" : defaultBaseUrl.trim();
  }

  ProcessorWorkerConfig asConfig() {
    return new ProcessorWorkerConfig(defaultEnabled, defaultBaseUrl);
  }
}
