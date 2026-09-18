package io.pockethive.clearingexport;

import io.pockethive.controlplane.filesystem.RuntimeFilesystemLayout;
import io.pockethive.controlplane.filesystem.RuntimeOutputDirectory;
import io.pockethive.controlplane.spring.WorkerControlPlaneProperties;
import io.pockethive.swarm.model.RuntimeFilesystemContract;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Responsibility: compose exporter storage from the shared mount and current runtime identity.
 * Must not: obtain output roots from scenario config, build path segments or persist files.
 * Contract: RESP-CLEARING-EXPORT — docs/architecture/runtime-responsibilities.md#resp-clearing-export.
 */
@Configuration(proxyBeanMethods = false)
class ClearingExportStorageConfiguration {
  @Bean
  ClearingExportSink clearingExportSink(WorkerControlPlaneProperties identity,
      @Value("${POCKETHIVE_JOURNAL_RUN_ID}") String runId) {
    var layout = RuntimeFilesystemLayout.of(
        RuntimeFilesystemContract.CONTAINER_ROOT, RuntimeFilesystemContract.CONTAINER_ROOT);
    return new LocalDirectoryClearingExportSink(new RuntimeOutputDirectory(
        layout.workerOutputDirectory(identity.getSwarmId(), runId, identity.getInstanceId())));
  }
}
