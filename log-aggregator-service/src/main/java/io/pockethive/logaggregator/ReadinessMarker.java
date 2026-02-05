package io.pockethive.logaggregator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
class ReadinessMarker implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(ReadinessMarker.class);
  private static final Path READY_FILE = Path.of("/tmp/pockethive-ready");

  @Override
  public void run(ApplicationArguments args) {
    try {
      Files.writeString(READY_FILE, Instant.now().toString(), StandardCharsets.UTF_8);
      log.info("readiness marker written to {}", READY_FILE);
    } catch (Exception e) {
      log.warn("failed to write readiness marker to {}", READY_FILE, e);
    }
  }
}

