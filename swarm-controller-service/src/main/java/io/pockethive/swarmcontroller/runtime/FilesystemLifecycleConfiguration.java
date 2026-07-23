package io.pockethive.swarmcontroller.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.controlplane.lifecycle.FilesystemSwarmRemoveStore;
import io.pockethive.swarm.model.SwarmStartupArtifactContract;
import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FilesystemLifecycleConfiguration {

  @Bean
  FilesystemSwarmRemoveStore filesystemSwarmRemoveStore(ObjectMapper mapper) {
    return new FilesystemSwarmRemoveStore(
        mapper, Path.of(SwarmStartupArtifactContract.CONTAINER_RUNTIME_ROOT));
  }
}
