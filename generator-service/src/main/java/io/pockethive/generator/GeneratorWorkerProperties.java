package io.pockethive.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.worker.sdk.config.CanonicalWorkerProperties;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "pockethive.workers.generator")
class GeneratorWorkerProperties extends CanonicalWorkerProperties<GeneratorWorkerConfig> {

  private static final GeneratorWorkerConfig FALLBACK = new GeneratorWorkerConfig(
      0.0,
      false,
      new GeneratorWorkerConfig.Message("/api/test", "POST", "", Map.of())
  );

  GeneratorWorkerProperties(ObjectMapper mapper) {
    super("generator", GeneratorWorkerConfig.class, mapper);
  }

  GeneratorWorkerConfig defaultConfig() {
    return toConfig(objectMapper()).orElse(FALLBACK);
  }
}
