package io.pockethive.trigger;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.controlplane.spring.WorkerControlPlaneProperties;
import io.pockethive.worker.sdk.config.CanonicalWorkerProperties;
import io.pockethive.worker.sdk.config.PocketHiveWorkerConfigProperties;
import org.springframework.stereotype.Component;

@Component
@PocketHiveWorkerConfigProperties
class TriggerWorkerProperties extends CanonicalWorkerProperties<TriggerWorkerConfig> {

  TriggerWorkerProperties(ObjectMapper mapper, WorkerControlPlaneProperties controlPlaneProperties) {
    super(() -> controlPlaneProperties.getWorker().getRole(), TriggerWorkerConfig.class, mapper);
  }

  TriggerWorkerConfig defaultConfig() {
    return toConfig(objectMapper()).orElseThrow(() ->
        new IllegalStateException("Missing trigger config under pockethive.worker.config"));
  }
}
