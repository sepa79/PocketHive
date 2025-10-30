package io.pockethive.moderator.shaper.config;

import io.pockethive.moderator.ModeratorWorkerConfig;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component("patternConfigValidator")
public class PatternConfigValidator {

  private static final double EPSILON = 1e-6;
  private static volatile PatternConfigValidator shared;

  public PatternConfigValidator() {
    this(true);
  }

  private PatternConfigValidator(boolean shareInstance) {
    if (shareInstance) {
      shared = this;
    }
  }

  public static ModeratorWorkerConfig ensureValid(ModeratorWorkerConfig config) {
    PatternConfigValidator validator = shared != null ? shared : new PatternConfigValidator(false);
    validator.validate(config);
    return config;
  }

  public static void ensureValid(PatternConfig pattern, NormalizationConfig normalization) {
    PatternConfigValidator validator = shared != null ? shared : new PatternConfigValidator(false);
    validator.validate(pattern, normalization);
  }

  public ModeratorWorkerConfig validate(ModeratorWorkerConfig config) {
    Objects.requireNonNull(config, "config");
    validate(Objects.requireNonNull(config.pattern(), "pattern"), config.normalization());
    return config;
  }

  public void validate(PatternConfig pattern, NormalizationConfig normalization) {
    Objects.requireNonNull(pattern, "pattern");
    Duration duration = pattern.duration();
    if (duration == null || duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException("pattern duration must be positive");
    }
    List<StepConfig> steps = pattern.steps();
    if (steps == null || steps.isEmpty()) {
      throw new IllegalArgumentException("pattern must declare at least one step");
    }

    List<StepConfig> ordered = new ArrayList<>(steps);
    ordered.sort(Comparator.comparing(step -> step.range().startFraction(duration)));

    double expectedStart = 0d;
    for (StepConfig step : ordered) {
      StepRangeConfig range = step.range();
      double start = range.startFraction(duration);
      double end = range.endFraction(duration);
      if (start + EPSILON < expectedStart) {
        throw new IllegalArgumentException("steps overlap around step '%s'".formatted(step.id()));
      }
      if (Math.abs(start - expectedStart) > EPSILON && start > expectedStart) {
        throw new IllegalArgumentException("steps do not cover the full pattern before step '%s'".formatted(step.id()));
      }
      double span = end - start;
      if (span <= 0) {
        throw new IllegalArgumentException("step '%s' has non-positive span".formatted(step.id()));
      }
      double transitionSpan = step.transition().type() == TransitionType.NONE
          ? 0d
          : computeTransitionFraction(step.transition(), range, duration);
      if (transitionSpan - span > EPSILON) {
        throw new IllegalArgumentException("transition for step '%s' exceeds step duration".formatted(step.id()));
      }
      expectedStart = end;
    }

    if (Math.abs(expectedStart - 1d) > EPSILON) {
      throw new IllegalArgumentException("steps do not cover the entire pattern duration");
    }

    if (normalization != null && normalization.enabled()) {
      BigDecimal tolerance = normalization.tolerancePct();
      if (tolerance == null) {
        throw new IllegalArgumentException("normalization tolerance is required when enabled");
      }
      if (tolerance.compareTo(BigDecimal.ZERO) < 0 || tolerance.compareTo(BigDecimal.valueOf(100)) > 0) {
        throw new IllegalArgumentException("normalization tolerance must be between 0 and 100 percent");
      }
    }

  }

  private double computeTransitionFraction(TransitionConfig transition,
                                           StepRangeConfig range,
                                           Duration patternDuration) {
    if (transition.percent() != null) {
      double percent = transition.percent().doubleValue() / 100d;
      return range.spanFraction(patternDuration) * percent;
    }
    if (transition.duration() != null) {
      long durationMillis = transition.duration().toMillis();
      long patternMillis = patternDuration.toMillis();
      if (patternMillis <= 0) {
        throw new IllegalArgumentException("pattern duration must be positive");
      }
      return durationMillis / (double) patternMillis;
    }
    return 0d;
  }
}
