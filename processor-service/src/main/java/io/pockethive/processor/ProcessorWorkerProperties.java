package io.pockethive.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.worker.sdk.config.CanonicalWorkerProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "pockethive.workers.processor")
class ProcessorWorkerProperties extends CanonicalWorkerProperties<ProcessorWorkerConfig> {

  private static final ProcessorWorkerConfig FALLBACK =
      new ProcessorWorkerConfig("http://localhost:8082");

  ProcessorWorkerProperties(ObjectMapper mapper) {
    super("processor", ProcessorWorkerConfig.class, mapper);
  }

  ProcessorWorkerConfig defaultConfig() {
    return toConfig(objectMapper()).orElse(FALLBACK);
  }
}
