package io.pockethive.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.worker.sdk.config.PocketHiveWorkerProperties;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "pockethive.workers.generator")
class GeneratorWorkerProperties extends PocketHiveWorkerProperties<GeneratorWorkerConfig> {

  private static final GeneratorWorkerConfig FALLBACK = new GeneratorWorkerConfig(
      0.0,
      false,
      new GeneratorWorkerConfig.Message("/api/test", "POST", "", Map.of())
  );

  private final ObjectMapper mapper;

  GeneratorWorkerProperties(ObjectMapper mapper) {
    super("generator", GeneratorWorkerConfig.class);
    this.mapper = mapper;
  }

  GeneratorWorkerConfig defaultConfig() {
    return toConfig(mapper).orElse(FALLBACK);
  }
}
