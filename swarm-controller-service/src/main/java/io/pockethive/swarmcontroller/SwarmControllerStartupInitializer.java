package io.pockethive.swarmcontroller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.controlplane.filesystem.FilesystemSwarmStartupArtifactLoader;
import io.pockethive.swarm.model.SwarmStartupArtifact;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Responsibility: Apply the verified filesystem startup artifact to the canonical lifecycle owner exactly once.
 * Must not: Consume control-plane messages, publish status, or own lifecycle state after initialization.
 * Contract: Readiness remains false until both swarm and scenario plans are serialized and applied successfully.
 */
@Component
public class SwarmControllerStartupInitializer {

  private static final Logger log = LoggerFactory.getLogger(SwarmControllerStartupInitializer.class);

  private final boolean initialized;
  private final String artifactSha256;
  private final Instant startedAt;

  @Autowired
  public SwarmControllerStartupInitializer(
      SwarmLifecycle lifecycle,
      ObjectMapper mapper,
      SwarmControllerProperties properties,
      FilesystemSwarmStartupArtifactLoader loader) {
    this(lifecycle, mapper, properties, loader, Clock.systemUTC());
  }

  SwarmControllerStartupInitializer(
      SwarmLifecycle lifecycle,
      ObjectMapper mapper,
      SwarmControllerProperties properties,
      FilesystemSwarmStartupArtifactLoader loader,
      Clock clock) {
    Objects.requireNonNull(lifecycle, "lifecycle");
    ObjectMapper resolvedMapper = Objects.requireNonNull(mapper, "mapper").findAndRegisterModules();
    String swarmId = Objects.requireNonNull(properties, "properties").getSwarmId();
    FilesystemSwarmStartupArtifactLoader resolvedLoader =
        Objects.requireNonNull(loader, "loader");
    this.artifactSha256 = resolvedLoader.expectedSha256();
    this.startedAt = Objects.requireNonNull(clock, "clock").instant();
    SwarmStartupArtifact artifact = resolvedLoader.load(swarmId);
    try {
      lifecycle.prepare(resolvedMapper.writeValueAsString(artifact.swarmPlan()));
      lifecycle.applyScenarioPlan(resolvedMapper.writeValueAsString(artifact.scenarioPlan()));
    } catch (JsonProcessingException failure) {
      throw new IllegalStateException(
          "Failed to serialize verified startup artifact for swarm " + swarmId, failure);
    }
    this.initialized = true;
    log.info("Initialized swarm {} from filesystem startup artifact", swarmId);
  }

  boolean isInitialized() {
    return initialized;
  }

  String artifactSha256() {
    return artifactSha256;
  }

  Instant startedAt() {
    return startedAt;
  }
}
