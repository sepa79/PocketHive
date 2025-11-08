package io.pockethive.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.worker.sdk.config.PocketHiveWorkerProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "pockethive.workers.processor")
class ProcessorWorkerProperties extends PocketHiveWorkerProperties<ProcessorWorkerConfig> {

  private static final ProcessorWorkerConfig FALLBACK =
      new ProcessorWorkerConfig("http://localhost:8082");

  private final ObjectMapper mapper;

  ProcessorWorkerProperties(ObjectMapper mapper) {
    super("processor", ProcessorWorkerConfig.class);
    this.mapper = mapper;
  }

  ProcessorWorkerConfig defaultConfig() {
    return toConfig(mapper).orElse(FALLBACK);
  }
}
