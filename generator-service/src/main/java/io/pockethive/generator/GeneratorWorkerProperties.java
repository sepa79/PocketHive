package io.pockethive.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.worker.sdk.config.CanonicalWorkerProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "pockethive.workers.generator")
class GeneratorWorkerProperties extends CanonicalWorkerProperties<GeneratorWorkerConfig> {


  GeneratorWorkerProperties(ObjectMapper mapper) {
    super("generator", GeneratorWorkerConfig.class, mapper);
  }

  GeneratorWorkerConfig defaultConfig() {
    return toConfig(objectMapper()).orElseThrow(() ->
        new IllegalStateException("Missing generator config under pockethive.workers.generator"));
  }
}
