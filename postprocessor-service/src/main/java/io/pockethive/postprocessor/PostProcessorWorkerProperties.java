package io.pockethive.postprocessor;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.worker.sdk.config.CanonicalWorkerProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "pockethive.workers.postprocessor")
class PostProcessorWorkerProperties extends CanonicalWorkerProperties<PostProcessorWorkerConfig> {

  PostProcessorWorkerProperties(ObjectMapper mapper) {
    super("postprocessor", PostProcessorWorkerConfig.class, mapper);
  }

  PostProcessorWorkerConfig defaultConfig() {
    return toConfig(objectMapper()).orElseThrow(() ->
        new IllegalStateException("Missing postprocessor config under pockethive.workers.postprocessor"));
  }
}
