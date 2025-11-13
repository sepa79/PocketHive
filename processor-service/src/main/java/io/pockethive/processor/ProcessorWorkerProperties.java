package io.pockethive.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.worker.sdk.config.CanonicalWorkerProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "pockethive.workers.processor")
class ProcessorWorkerProperties extends CanonicalWorkerProperties<ProcessorWorkerConfig> {

  ProcessorWorkerProperties(ObjectMapper mapper) {
    super("processor", ProcessorWorkerConfig.class, mapper);
  }

  ProcessorWorkerConfig defaultConfig() {
    return toConfig(objectMapper()).orElseThrow(() ->
        new IllegalStateException("Missing processor config under pockethive.workers.processor"));
  }
}
