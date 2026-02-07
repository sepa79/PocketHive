package io.pockethive.httpsequence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.controlplane.spring.WorkerControlPlaneProperties;
import io.pockethive.worker.sdk.config.CanonicalWorkerProperties;
import io.pockethive.worker.sdk.config.PocketHiveWorkerConfigProperties;
import org.springframework.stereotype.Component;

@Component
@PocketHiveWorkerConfigProperties
class HttpSequenceWorkerProperties extends CanonicalWorkerProperties<HttpSequenceWorkerConfig> {

  HttpSequenceWorkerProperties(ObjectMapper mapper, WorkerControlPlaneProperties controlPlaneProperties) {
    super(() -> controlPlaneProperties.getWorker().getRole(), HttpSequenceWorkerConfig.class, mapper);
  }

  HttpSequenceWorkerConfig defaultConfig() {
    return toConfig(objectMapper()).orElseGet(
        () -> new HttpSequenceWorkerConfig(null, "/app/http-templates", "default", 1, java.util.List.of(), null, java.util.Map.of()));
  }
}

