package io.pockethive.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.controlplane.spring.WorkerControlPlaneProperties;
import io.pockethive.worker.sdk.config.CanonicalWorkerProperties;
import io.pockethive.worker.sdk.config.PocketHiveWorkerConfigProperties;
import org.springframework.stereotype.Component;

@Component
@PocketHiveWorkerConfigProperties
class ProcessorWorkerProperties extends CanonicalWorkerProperties<ProcessorWorkerConfig> {

  ProcessorWorkerProperties(ObjectMapper mapper, WorkerControlPlaneProperties controlPlaneProperties) {
    super(() -> controlPlaneProperties.getWorker().getRole(), ProcessorWorkerConfig.class, mapper);
  }

  ProcessorWorkerConfig defaultConfig() {
    return toConfig(objectMapper()).orElseThrow(() ->
        new IllegalStateException("Missing processor config under pockethive.worker.config"));
  }
}
