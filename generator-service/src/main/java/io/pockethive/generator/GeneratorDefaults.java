package io.pockethive.generator;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class GeneratorDefaults {

  private final MessageConfig messageConfig;
  private final double defaultRatePerSec;
  private final boolean defaultEnabled;

  GeneratorDefaults(MessageConfig messageConfig,
                    @Value("${ph.gen.ratePerSec:0}") double defaultRatePerSec,
                    @Value("${ph.gen.enabled:false}") boolean defaultEnabled) {
    this.messageConfig = messageConfig;
    this.defaultRatePerSec = defaultRatePerSec;
    this.defaultEnabled = defaultEnabled;
  }

  GeneratorWorkerConfig asConfig() {
    return new GeneratorWorkerConfig(
        defaultEnabled,
        defaultRatePerSec,
        false,
        resolvePath(),
        resolveMethod(),
        resolveBody(),
        resolveHeaders()
    );
  }

  private String resolvePath() {
    String path = messageConfig.getPath();
    return (path == null || path.isBlank()) ? "/" : path.trim();
  }

  private String resolveMethod() {
    String method = messageConfig.getMethod();
    return (method == null || method.isBlank()) ? "GET" : method.trim();
  }

  private String resolveBody() {
    String body = messageConfig.getBody();
    return body == null ? "" : body;
  }

  private Map<String, String> resolveHeaders() {
    Map<String, String> headers = messageConfig.getHeaders();
    if (headers == null || headers.isEmpty()) {
      return Map.of();
    }
    return Map.copyOf(headers);
  }
}
