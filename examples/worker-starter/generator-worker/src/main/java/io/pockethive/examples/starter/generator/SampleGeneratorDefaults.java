package io.pockethive.examples.starter.generator;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Resolves local defaults for the generator worker when no control-plane overrides exist.
 */
@Component
@ConfigurationProperties(prefix = "starter.generator")
class SampleGeneratorDefaults {

  private boolean enabled = true;
  private double ratePerSecond = 1.0;
  private String message = "Hello from the generator";

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public double getRatePerSecond() {
    return ratePerSecond;
  }

  public void setRatePerSecond(double ratePerSecond) {
    this.ratePerSecond = ratePerSecond;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  SampleGeneratorConfig asConfig() {
    return new SampleGeneratorConfig(enabled, ratePerSecond, message);
  }
}
