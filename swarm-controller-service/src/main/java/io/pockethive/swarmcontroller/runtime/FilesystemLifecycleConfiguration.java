package io.pockethive.swarmcontroller.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.controlplane.filesystem.FilesystemSwarmRemoveStore;
import io.pockethive.controlplane.filesystem.FilesystemSwarmStartupArtifactLoader;
import io.pockethive.controlplane.filesystem.FilesystemSwarmStartupArtifactStore;
import io.pockethive.controlplane.filesystem.RuntimeFilesystemLayout;
import io.pockethive.controlplane.filesystem.RuntimeFilesystemMount;
import io.pockethive.swarm.model.RuntimeFilesystemContract;
import io.pockethive.swarm.model.SwarmStartupArtifactContract;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FilesystemLifecycleConfiguration {

  @Bean
  RuntimeFilesystemLayout runtimeFilesystemLayout(
      @Value("${" + RuntimeFilesystemContract.LOCAL_ROOT_ENV + ":}") String runtimeRoot) {
    return RuntimeFilesystemLayout.of(runtimeRoot, runtimeRoot);
  }

  @Bean
  RuntimeFilesystemMount runtimeFilesystemMount(
      @Value("${" + RuntimeFilesystemContract.HOST_ROOT_ENV + ":}") String hostRoot) {
    return RuntimeFilesystemMount.of(hostRoot);
  }

  @Bean
  FilesystemSwarmStartupArtifactStore filesystemSwarmStartupArtifactStore(
      ObjectMapper mapper, RuntimeFilesystemLayout layout) {
    return new FilesystemSwarmStartupArtifactStore(mapper, layout);
  }

  @Bean
  FilesystemSwarmStartupArtifactLoader filesystemSwarmStartupArtifactLoader(
      FilesystemSwarmStartupArtifactStore store,
      @Value("${" + SwarmStartupArtifactContract.PATH_ENV + ":}") String artifactPath,
      @Value("${" + SwarmStartupArtifactContract.SHA256_ENV + ":}") String expectedSha256) {
    return new FilesystemSwarmStartupArtifactLoader(store, artifactPath, expectedSha256);
  }

  @Bean
  FilesystemSwarmRemoveStore filesystemSwarmRemoveStore(
      ObjectMapper mapper, RuntimeFilesystemLayout layout) {
    return new FilesystemSwarmRemoveStore(mapper, layout);
  }
}
