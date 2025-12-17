package io.pockethive.orchestrator.infra;

import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ScenariosRuntimeRootInitializer {

  private static final Logger log = LoggerFactory.getLogger(ScenariosRuntimeRootInitializer.class);
  private static final String SCENARIOS_RUNTIME_ROOT = "scenarios-runtime";

  @PostConstruct
  public void ensureRuntimeRootExists() {
    Path root = Paths.get(SCENARIOS_RUNTIME_ROOT).toAbsolutePath().normalize();
    try {
      Files.createDirectories(root);
      log.info("Scenarios runtime root: {}", root);
    } catch (Exception e) {
      throw new IllegalStateException("Unable to create scenarios runtime root directory: " + root, e);
    }
  }
}
