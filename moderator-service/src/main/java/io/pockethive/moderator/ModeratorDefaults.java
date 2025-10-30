package io.pockethive.moderator;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "pockethive.control-plane.worker.moderator")
class ModeratorDefaults {

  private boolean enabled = false;
  private ModeProperties mode = new ModeProperties();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public ModeProperties getMode() {
    return mode;
  }

  public void setMode(ModeProperties mode) {
    this.mode = mode == null ? new ModeProperties() : mode;
  }

  ModeratorWorkerConfig asConfig() {
    return new ModeratorWorkerConfig(enabled, mode.toMode());
  }

  static final class ModeProperties {

    private ModeratorWorkerConfig.Mode.Type type = ModeratorWorkerConfig.Mode.Type.PASS_THROUGH;
    private RatePerSecProperties ratePerSec = new RatePerSecProperties();
    private SineProperties sine = new SineProperties();

    ModeratorWorkerConfig.Mode toMode() {
      return new ModeratorWorkerConfig.Mode(
          type,
          new ModeratorWorkerConfig.Mode.RatePerSec(ratePerSec.getValue()),
          new ModeratorWorkerConfig.Mode.Sine(
              sine.getMinRatePerSec(),
              sine.getMaxRatePerSec(),
              sine.getPeriodSeconds(),
              sine.getPhaseOffsetSeconds()));
    }

    public ModeratorWorkerConfig.Mode.Type getType() {
      return type;
    }

    public void setType(ModeratorWorkerConfig.Mode.Type type) {
      this.type = type == null ? ModeratorWorkerConfig.Mode.Type.PASS_THROUGH : type;
    }

    public RatePerSecProperties getRatePerSec() {
      return ratePerSec;
    }

    public void setRatePerSec(RatePerSecProperties ratePerSec) {
      this.ratePerSec = ratePerSec == null ? new RatePerSecProperties() : ratePerSec;
    }

    public SineProperties getSine() {
      return sine;
    }

    public void setSine(SineProperties sine) {
      this.sine = sine == null ? new SineProperties() : sine;
    }
  }

  static final class RatePerSecProperties {

    private double value = 0.0;

    public double getValue() {
      return value;
    }

    public void setValue(double value) {
      this.value = value;
    }
  }

  static final class SineProperties {

    private double minRatePerSec = 0.0;
    private double maxRatePerSec = 0.0;
    private double periodSeconds = 60.0;
    private double phaseOffsetSeconds = 0.0;

    public double getMinRatePerSec() {
      return minRatePerSec;
    }

    public void setMinRatePerSec(double minRatePerSec) {
      this.minRatePerSec = minRatePerSec;
    }

    public double getMaxRatePerSec() {
      return maxRatePerSec;
    }

    public void setMaxRatePerSec(double maxRatePerSec) {
      this.maxRatePerSec = maxRatePerSec;
    }

    public double getPeriodSeconds() {
      return periodSeconds;
    }

    public void setPeriodSeconds(double periodSeconds) {
      this.periodSeconds = periodSeconds;
    }

    public double getPhaseOffsetSeconds() {
      return phaseOffsetSeconds;
    }

    public void setPhaseOffsetSeconds(double phaseOffsetSeconds) {
      this.phaseOffsetSeconds = phaseOffsetSeconds;
    }
  }
}
