package io.pockethive.httpbuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.controlplane.spring.WorkerControlPlaneProperties;
import io.pockethive.worker.sdk.config.CanonicalWorkerProperties;
import io.pockethive.worker.sdk.config.PocketHiveWorkerConfigProperties;
import org.springframework.stereotype.Component;

@Component
@PocketHiveWorkerConfigProperties
class HttpBuilderWorkerProperties extends CanonicalWorkerProperties<HttpBuilderWorkerConfig> {

  HttpBuilderWorkerProperties(ObjectMapper mapper, WorkerControlPlaneProperties controlPlaneProperties) {
    super(() -> controlPlaneProperties.getWorker().getRole(), HttpBuilderWorkerConfig.class, mapper);
  }

  HttpBuilderWorkerConfig defaultConfig() {
    return toConfig(objectMapper()).orElseGet(() -> new HttpBuilderWorkerConfig("/app/http-templates", "default", true));
  }
}
