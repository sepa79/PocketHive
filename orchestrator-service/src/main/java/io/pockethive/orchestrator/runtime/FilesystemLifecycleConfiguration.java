package io.pockethive.orchestrator.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.controlplane.lifecycle.FilesystemSwarmRemoveStore;
import io.pockethive.swarm.model.SwarmStartupArtifactContract;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FilesystemLifecycleConfiguration {

  @Bean
  FilesystemSwarmRemoveStore filesystemSwarmRemoveStore(
      ObjectMapper mapper,
      @Value("${" + SwarmStartupArtifactContract.WRITE_ROOT_ENV + ":}") String runtimeRoot) {
    if (runtimeRoot == null || runtimeRoot.isBlank()) {
      throw new IllegalStateException(SwarmStartupArtifactContract.WRITE_ROOT_ENV + " must not be blank");
    }
    return new FilesystemSwarmRemoveStore(mapper, Path.of(runtimeRoot));
  }
}
