package io.pockethive.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.controlplane.spring.WorkerControlPlaneProperties;
import io.pockethive.worker.sdk.config.CanonicalWorkerProperties;
import io.pockethive.worker.sdk.config.PocketHiveWorkerConfigProperties;
import org.springframework.stereotype.Component;

@Component
@PocketHiveWorkerConfigProperties
class GeneratorWorkerProperties extends CanonicalWorkerProperties<GeneratorWorkerConfig> {


  GeneratorWorkerProperties(ObjectMapper mapper, WorkerControlPlaneProperties controlPlaneProperties) {
    super(() -> controlPlaneProperties.getWorker().getRole(), GeneratorWorkerConfig.class, mapper);
  }

  GeneratorWorkerConfig defaultConfig() {
    return toConfig(objectMapper()).orElseThrow(() ->
        new IllegalStateException("Missing generator config under pockethive.worker.config"));
  }
}
