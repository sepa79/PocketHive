package io.pockethive.dataprovider;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.controlplane.spring.WorkerControlPlaneProperties;
import io.pockethive.worker.sdk.config.CanonicalWorkerProperties;
import io.pockethive.worker.sdk.config.PocketHiveWorkerConfigProperties;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
@PocketHiveWorkerConfigProperties
class DataProviderWorkerProperties extends CanonicalWorkerProperties<DataProviderWorkerConfig> {

  DataProviderWorkerProperties(ObjectMapper mapper, WorkerControlPlaneProperties controlPlaneProperties) {
    super(() -> controlPlaneProperties.getWorker().getRole(), DataProviderWorkerConfig.class, mapper);
  }

  DataProviderWorkerConfig defaultConfig() {
    return toConfig(objectMapper()).orElseGet(() -> new DataProviderWorkerConfig(Map.of()));
  }
}
