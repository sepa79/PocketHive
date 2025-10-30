package io.pockethive.generator;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "pockethive.control-plane.worker.generator")
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
        new GeneratorWorkerConfig.Message(
            messageConfig.getPath(),
            messageConfig.getMethod(),
            messageConfig.getBody(),
            messageConfig.getHeaders()
        )
    );
  }
}
