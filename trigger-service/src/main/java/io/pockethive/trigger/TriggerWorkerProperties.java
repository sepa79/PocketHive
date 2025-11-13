package io.pockethive.trigger;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.worker.sdk.config.CanonicalWorkerProperties;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "pockethive.workers.trigger")
class TriggerWorkerProperties extends CanonicalWorkerProperties<TriggerWorkerConfig> {

  TriggerWorkerProperties(ObjectMapper mapper) {
    super("trigger", TriggerWorkerConfig.class, mapper);
  }

  TriggerWorkerConfig defaultConfig() {
    return toConfig(objectMapper()).orElseThrow(() ->
        new IllegalStateException("Missing trigger config under pockethive.workers.trigger"));
  }
}
