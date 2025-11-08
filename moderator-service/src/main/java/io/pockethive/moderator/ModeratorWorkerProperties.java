package io.pockethive.moderator;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.worker.sdk.config.PocketHiveWorkerProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "pockethive.workers.moderator")
class ModeratorWorkerProperties extends PocketHiveWorkerProperties<ModeratorWorkerConfig> {

  private static final ModeratorWorkerConfig FALLBACK =
      new ModeratorWorkerConfig(false, ModeratorWorkerConfig.Mode.passThrough());

  private final ObjectMapper mapper;

  ModeratorWorkerProperties(ObjectMapper mapper) {
    super("moderator", ModeratorWorkerConfig.class);
    this.mapper = mapper;
  }

  ModeratorWorkerConfig defaultConfig() {
    return toConfig(mapper).orElse(FALLBACK);
  }
}
