package io.pockethive.orchestrator.infra;

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

  private final String runtimeRoot;

  public ScenariosRuntimeRootInitializer(@Value("${pockethive.scenarios.runtime-root:}") String runtimeRoot) {
    this.runtimeRoot = runtimeRoot;
  }

  @PostConstruct
  public void ensureRuntimeRootExists() {
    if (runtimeRoot == null || runtimeRoot.isBlank()) {
      return;
    }
    Path root = Paths.get(runtimeRoot).toAbsolutePath().normalize();
    try {
      Files.createDirectories(root);
      log.info("Scenarios runtime root: {}", root);
    } catch (Exception e) {
      throw new IllegalStateException("Unable to create scenarios runtime root directory: " + root, e);
    }
  }
}

