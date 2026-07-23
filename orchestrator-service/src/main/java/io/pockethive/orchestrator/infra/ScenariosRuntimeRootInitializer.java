package io.pockethive.orchestrator.infra;

import io.pockethive.swarm.model.SwarmStartupArtifactContract;
import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ScenariosRuntimeRootInitializer {

  private static final Logger log = LoggerFactory.getLogger(ScenariosRuntimeRootInitializer.class);
  private final Path root;

  public ScenariosRuntimeRootInitializer(
      @Value("${" + SwarmStartupArtifactContract.WRITE_ROOT_ENV + ":}") String runtimeRoot) {
    if (runtimeRoot == null || runtimeRoot.isBlank()) {
      throw new IllegalStateException(SwarmStartupArtifactContract.WRITE_ROOT_ENV + " must not be blank");
    }
    this.root = Paths.get(runtimeRoot).toAbsolutePath().normalize();
  }

  @PostConstruct
  public void ensureRuntimeRootExists() {
    try {
      Files.createDirectories(root);
      log.info("Scenarios runtime root: {}", root);
    } catch (Exception e) {
      throw new IllegalStateException("Unable to create scenarios runtime root directory: " + root, e);
    }
  }
}
