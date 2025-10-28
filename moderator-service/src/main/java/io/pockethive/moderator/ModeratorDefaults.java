package io.pockethive.moderator;

import io.pockethive.moderator.shaper.config.GlobalMutatorConfig;
import io.pockethive.moderator.shaper.config.JitterConfig;
import io.pockethive.moderator.shaper.config.JitterType;
import io.pockethive.moderator.shaper.config.NormalizationConfig;
import io.pockethive.moderator.shaper.config.PatternConfig;
import io.pockethive.moderator.shaper.config.PatternConfigValidator;
import io.pockethive.moderator.shaper.config.RepeatAlignment;
import io.pockethive.moderator.shaper.config.RepeatConfig;
import io.pockethive.moderator.shaper.config.RepeatUntil;
import io.pockethive.moderator.shaper.config.RunConfig;
import io.pockethive.moderator.shaper.config.SeedsConfig;
import io.pockethive.moderator.shaper.config.StepConfig;
import io.pockethive.moderator.shaper.config.StepMode;
import io.pockethive.moderator.shaper.config.StepMutatorConfig;
import io.pockethive.moderator.shaper.config.StepRangeConfig;
import io.pockethive.moderator.shaper.config.StepRangeUnit;
import io.pockethive.moderator.shaper.config.TimeConfig;
import io.pockethive.moderator.shaper.config.TimeMode;
import io.pockethive.moderator.shaper.config.TransitionConfig;
import io.pockethive.moderator.shaper.config.TransitionType;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "pockethive.control-plane.worker.moderator")
class ModeratorDefaults {

  private boolean enabled = false;
  private TimeConfig time = new TimeConfig(TimeMode.WARP, BigDecimal.ONE, ZoneId.of("Europe/London"));
  private RunConfig run = new RunConfig(Duration.ofDays(30));
  private PatternConfig pattern = defaultPattern();
  private NormalizationConfig normalization = new NormalizationConfig(true, BigDecimal.valueOf(5));
  private List<GlobalMutatorConfig> globalMutators = new ArrayList<>(List.of(
      new GlobalMutatorConfig("spike", Map.of(
          "at", "12:00",
          "width", "5m",
          "liftPct", 40
      ))));
  private JitterConfig jitter = new JitterConfig(JitterType.SEQUENCE, Duration.ofMillis(50), "ph/mod/run-001", Duration.ofSeconds(1));
  private SeedsConfig seeds = new SeedsConfig("ph/mod/default-seed", Map.of(
      "burst", "ph/mod/burst-01",
      "noise", "ph/mod/noise-01",
      "jitter", "ph/mod/run-001"
  ));
  private final PatternConfigValidator validator;

  ModeratorDefaults(PatternConfigValidator validator) {
    this.validator = validator;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public TimeConfig getTime() {
    return time;
  }

  public void setTime(TimeConfig time) {
    this.time = time;
  }

  public RunConfig getRun() {
    return run;
  }

  public void setRun(RunConfig run) {
    this.run = run;
  }

  public PatternConfig getPattern() {
    return pattern;
  }

  public void setPattern(PatternConfig pattern) {
    this.pattern = pattern;
  }

  public NormalizationConfig getNormalization() {
    return normalization;
  }

  public void setNormalization(NormalizationConfig normalization) {
    this.normalization = normalization;
  }

  public List<GlobalMutatorConfig> getGlobalMutators() {
    return globalMutators;
  }

  public void setGlobalMutators(List<GlobalMutatorConfig> globalMutators) {
    this.globalMutators = globalMutators == null ? new ArrayList<>() : new ArrayList<>(globalMutators);
  }

  public JitterConfig getJitter() {
    return jitter;
  }

  public void setJitter(JitterConfig jitter) {
    this.jitter = jitter;
  }

  public SeedsConfig getSeeds() {
    return seeds;
  }

  public void setSeeds(SeedsConfig seeds) {
    this.seeds = seeds;
  }

  ModeratorWorkerConfig asConfig() {
    Objects.requireNonNull(pattern, "pattern field");
    ModeratorWorkerConfig config = new ModeratorWorkerConfig(
        enabled,
        time,
        run,
        pattern,
        normalization,
        globalMutators,
        jitter,
        seeds
    );
    validator.validate(config);
    return config;
  }

  private static PatternConfig defaultPattern() {
    List<StepConfig> steps = List.of(
        new StepConfig(
            "overnight",
            new StepRangeConfig(StepRangeUnit.PERCENT, null, null, BigDecimal.ZERO, BigDecimal.valueOf(20)),
            StepMode.FLAT,
            Map.of("factor", BigDecimal.valueOf(0.3)),
            List.of(new StepMutatorConfig("noise", Map.of("pct", 2, "seed", "ph/mod/noise-01"))),
            new TransitionConfig(TransitionType.SMOOTH, null, BigDecimal.valueOf(5))
        ),
        new StepConfig(
            "ramp-up",
            new StepRangeConfig(StepRangeUnit.PERCENT, null, null, BigDecimal.valueOf(20), BigDecimal.valueOf(45)),
            StepMode.RAMP,
            Map.of("from", BigDecimal.valueOf(0.3), "to", BigDecimal.valueOf(1.1)),
            List.of(new StepMutatorConfig("burst", Map.of(
                "liftPct", 25,
                "durationMs", Map.of("min", 5000, "max", 15000),
                "everyMs", Map.of("min", 300000, "max", 600000),
                "jitterStartMs", 500,
                "seed", "ph/mod/burst-01"
            ))),
            new TransitionConfig(TransitionType.LINEAR, null, BigDecimal.valueOf(5))
        ),
        new StepConfig(
            "peak",
            new StepRangeConfig(StepRangeUnit.PERCENT, null, null, BigDecimal.valueOf(45), BigDecimal.valueOf(75)),
            StepMode.FLAT,
            Map.of("factor", BigDecimal.valueOf(1.2)),
            List.of(new StepMutatorConfig("cap", Map.of(
                "min", BigDecimal.valueOf(0.2),
                "max", BigDecimal.valueOf(1.5)
            ))),
            new TransitionConfig(TransitionType.SMOOTH, null, BigDecimal.valueOf(5))
        ),
        new StepConfig(
            "taper",
            new StepRangeConfig(StepRangeUnit.PERCENT, null, null, BigDecimal.valueOf(75), BigDecimal.valueOf(100)),
            StepMode.RAMP,
            Map.of("from", BigDecimal.ONE, "to", BigDecimal.valueOf(0.4)),
            List.of(),
            TransitionConfig.none()
        )
    );
    return new PatternConfig(
        Duration.ofHours(24),
        BigDecimal.valueOf(1000),
        new RepeatConfig(true, RepeatUntil.TOTAL_TIME, null, RepeatAlignment.FROM_START),
        steps
    );
  }
}
