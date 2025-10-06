package io.pockethive.generator;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ph.gen")
class GeneratorDefaults {

  private final MessageConfig messageConfig;
  private double ratePerSec = 0;
  private boolean enabled = false;

  GeneratorDefaults(MessageConfig messageConfig) {
    this.messageConfig = messageConfig;
  }

  public double getRatePerSec() {
    return ratePerSec;
  }

  public void setRatePerSec(double ratePerSec) {
    this.ratePerSec = ratePerSec;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  GeneratorWorkerConfig asConfig() {
    return new GeneratorWorkerConfig(
        enabled,
        ratePerSec,
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
