package io.pockethive.postprocessor;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.worker.sdk.config.PocketHiveWorkerProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "pockethive.workers.postprocessor")
class PostProcessorWorkerProperties extends PocketHiveWorkerProperties<PostProcessorWorkerConfig> {

  private static final PostProcessorWorkerConfig FALLBACK = new PostProcessorWorkerConfig(false);

  private final ObjectMapper mapper;

  PostProcessorWorkerProperties(ObjectMapper mapper) {
    super("postprocessor", PostProcessorWorkerConfig.class);
    this.mapper = mapper;
  }

  PostProcessorWorkerConfig defaultConfig() {
    return toConfig(mapper).orElse(FALLBACK);
  }
}
