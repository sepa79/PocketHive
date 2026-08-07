package io.pockethive.functionalswarmreply;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.controlplane.spring.WorkerControlPlaneProperties;
import io.pockethive.worker.sdk.config.CanonicalWorkerProperties;
import io.pockethive.worker.sdk.config.PocketHiveWorkerConfigProperties;
import org.springframework.stereotype.Component;

@Component
@PocketHiveWorkerConfigProperties
class FunctionalSwarmReplyWorkerProperties extends CanonicalWorkerProperties<FunctionalSwarmReplyWorkerConfig> {
  FunctionalSwarmReplyWorkerProperties(ObjectMapper mapper, WorkerControlPlaneProperties controlPlaneProperties) {
    super(() -> controlPlaneProperties.getWorker().getRole(), FunctionalSwarmReplyWorkerConfig.class, mapper);
  }
}
