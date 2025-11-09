package io.pockethive.trigger;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.worker.sdk.config.CanonicalWorkerProperties;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "pockethive.workers.trigger")
class TriggerWorkerProperties extends CanonicalWorkerProperties<TriggerWorkerConfig> {

  private static final TriggerWorkerConfig FALLBACK = new TriggerWorkerConfig(
      1000L,
      false,
      "none",
      "",
      "",
      "GET",
      "",
      Map.of()
  );

  TriggerWorkerProperties(ObjectMapper mapper) {
    super("trigger", TriggerWorkerConfig.class, mapper);
  }

  TriggerWorkerConfig defaultConfig() {
    return toConfig(objectMapper()).orElse(FALLBACK);
  }
}
