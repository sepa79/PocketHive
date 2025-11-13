package io.pockethive.moderator;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.worker.sdk.config.CanonicalWorkerProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "pockethive.workers.moderator")
class ModeratorWorkerProperties extends CanonicalWorkerProperties<ModeratorWorkerConfig> {

  ModeratorWorkerProperties(ObjectMapper mapper) {
    super("moderator", ModeratorWorkerConfig.class, mapper);
  }

  ModeratorWorkerConfig defaultConfig() {
    return toConfig(objectMapper()).orElseThrow(() ->
        new IllegalStateException("Missing moderator config under pockethive.workers.moderator"));
  }
}
