package io.pockethive.functionalswarmingress;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.controlplane.spring.WorkerControlPlaneProperties;
import io.pockethive.worker.sdk.config.CanonicalWorkerProperties;
import io.pockethive.worker.sdk.config.PocketHiveWorkerConfigProperties;
import org.springframework.stereotype.Component;

@Component
@PocketHiveWorkerConfigProperties
class FunctionalSwarmIngressWorkerProperties extends CanonicalWorkerProperties<FunctionalSwarmIngressWorkerConfig> {
  FunctionalSwarmIngressWorkerProperties(ObjectMapper mapper, WorkerControlPlaneProperties controlPlaneProperties) {
    super(() -> controlPlaneProperties.getWorker().getRole(), FunctionalSwarmIngressWorkerConfig.class, mapper);
  }
}
