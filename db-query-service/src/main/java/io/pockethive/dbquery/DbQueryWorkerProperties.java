package io.pockethive.dbquery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.controlplane.spring.WorkerControlPlaneProperties;
import io.pockethive.worker.sdk.config.CanonicalWorkerProperties;
import io.pockethive.worker.sdk.config.PocketHiveWorkerConfigProperties;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
@PocketHiveWorkerConfigProperties
class DbQueryWorkerProperties extends CanonicalWorkerProperties<DbQueryWorkerConfig> {

  DbQueryWorkerProperties(ObjectMapper mapper, WorkerControlPlaneProperties controlPlaneProperties) {
    super(() -> controlPlaneProperties.getWorker().getRole(), DbQueryWorkerConfig.class, mapper);
  }

  DbQueryWorkerConfig defaultConfig() {
    return toConfig(objectMapper()).orElseGet(() -> new DbQueryWorkerConfig(
        null,
        "/app/templates/db",
        null,
        null,
        1,
        30000,
        null,
        DbQueryWorkerConfig.Pool.defaults(),
        DbQueryWorkerConfig.Retry.noRetry(),
        Map.of()));
  }
}
