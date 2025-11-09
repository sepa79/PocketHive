package io.pockethive.examples.starter.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.worker.sdk.config.CanonicalWorkerProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "pockethive.workers.generator")
class SampleGeneratorProperties extends CanonicalWorkerProperties<SampleGeneratorConfig> {

  private static final SampleGeneratorConfig FALLBACK =
      new SampleGeneratorConfig(1.0, "Hello from the generator");

  SampleGeneratorProperties(ObjectMapper mapper) {
    super("generator", SampleGeneratorConfig.class, mapper);
  }

  SampleGeneratorConfig defaultConfig() {
    return toConfig(objectMapper()).orElse(FALLBACK);
  }
}
