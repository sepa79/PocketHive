package io.pockethive.orchestrator.infra;

import io.pockethive.controlplane.filesystem.RuntimeFilesystemLayout;
import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ScenariosRuntimeRootInitializer {

  private static final Logger log = LoggerFactory.getLogger(ScenariosRuntimeRootInitializer.class);
  private final Path root;

  public ScenariosRuntimeRootInitializer(RuntimeFilesystemLayout layout) {
    this.root = layout.localRoot();
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
