package io.pockethive.clearingexport;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.controlplane.spring.WorkerControlPlaneProperties;
import io.pockethive.worker.sdk.config.CanonicalWorkerProperties;
import io.pockethive.worker.sdk.config.PocketHiveWorkerConfigProperties;
import org.springframework.stereotype.Component;

@Component
@PocketHiveWorkerConfigProperties
class ClearingExportWorkerProperties extends CanonicalWorkerProperties<ClearingExportWorkerConfig> {

  ClearingExportWorkerProperties(WorkerControlPlaneProperties controlPlaneProperties) {
    super(
        () -> controlPlaneProperties.getWorker().getRole(),
        ClearingExportWorkerConfig.class,
        new ObjectMapper().findAndRegisterModules());
  }

  ClearingExportWorkerConfig defaultConfig() {
    return toConfig(objectMapper()).orElseThrow(() ->
        new IllegalStateException("Missing clearing-export config under pockethive.worker.config"));
  }
}
