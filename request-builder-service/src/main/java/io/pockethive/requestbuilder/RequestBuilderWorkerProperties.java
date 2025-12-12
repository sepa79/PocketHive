package io.pockethive.requestbuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.controlplane.spring.WorkerControlPlaneProperties;
import io.pockethive.worker.sdk.config.CanonicalWorkerProperties;
import io.pockethive.worker.sdk.config.PocketHiveWorkerConfigProperties;
import org.springframework.stereotype.Component;

@Component
@PocketHiveWorkerConfigProperties
class RequestBuilderWorkerProperties extends CanonicalWorkerProperties<RequestBuilderWorkerConfig> {

  RequestBuilderWorkerProperties(ObjectMapper mapper, WorkerControlPlaneProperties controlPlaneProperties) {
    super(() -> controlPlaneProperties.getWorker().getRole(), RequestBuilderWorkerConfig.class, mapper);
  }

  RequestBuilderWorkerConfig defaultConfig() {
    return toConfig(objectMapper()).orElseGet(() -> new RequestBuilderWorkerConfig("/app/http-templates", "default", true));
  }
}
