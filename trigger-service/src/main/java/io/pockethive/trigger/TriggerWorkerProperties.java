package io.pockethive.trigger;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.worker.sdk.config.PocketHiveWorkerProperties;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "pockethive.workers.trigger")
class TriggerWorkerProperties extends PocketHiveWorkerProperties<TriggerWorkerConfig> {

  private static final TriggerWorkerConfig FALLBACK = new TriggerWorkerConfig(
      false,
      1000L,
      false,
      "none",
      "",
      "",
      "GET",
      "",
      Map.of()
  );

  private final ObjectMapper mapper;

  TriggerWorkerProperties(ObjectMapper mapper) {
    super("trigger", TriggerWorkerConfig.class);
    this.mapper = mapper;
  }

  TriggerWorkerConfig defaultConfig() {
    return toConfig(mapper).orElse(FALLBACK);
  }
}
