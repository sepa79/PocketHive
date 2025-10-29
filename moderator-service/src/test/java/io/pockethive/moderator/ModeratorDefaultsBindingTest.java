package io.pockethive.moderator;

import io.pockethive.moderator.shaper.config.JitterType;
import io.pockethive.moderator.shaper.config.PatternConfig;
import io.pockethive.moderator.shaper.config.PatternConfigValidator;
import io.pockethive.moderator.shaper.config.RepeatAlignment;
import io.pockethive.moderator.shaper.config.RepeatConfig;
import io.pockethive.moderator.shaper.config.RepeatUntil;
import io.pockethive.moderator.shaper.config.StepConfig;
import io.pockethive.moderator.shaper.config.StepMode;
import io.pockethive.moderator.shaper.config.StepRangeConfig;
import io.pockethive.moderator.shaper.config.StepRangeUnit;
import io.pockethive.moderator.shaper.config.TransitionType;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ModeratorDefaultsBindingTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withBean(PatternConfigValidator.class)
      .withBean(ModeratorDefaults.class);

  @Test
  void bindsScenarioPropertiesIntoModeratorConfig() {
    contextRunner
        .withPropertyValues(
            "pockethive.control-plane.worker.moderator.enabled=true",
            "pockethive.control-plane.worker.moderator.time.mode=warp",
            "pockethive.control-plane.worker.moderator.time.warp-factor=1",
            "pockethive.control-plane.worker.moderator.time.tz=UTC",
            "pockethive.control-plane.worker.moderator.run.total-time=PT1H",
            "pockethive.control-plane.worker.moderator.pattern.duration=PT4M",
            "pockethive.control-plane.worker.moderator.pattern.base-rate-rps=50",
            "pockethive.control-plane.worker.moderator.pattern.repeat.enabled=true",
            "pockethive.control-plane.worker.moderator.pattern.repeat.until=total_time",
            "pockethive.control-plane.worker.moderator.pattern.repeat.align=from_start",
            "pockethive.control-plane.worker.moderator.pattern.steps[0].id=flat",
            "pockethive.control-plane.worker.moderator.pattern.steps[0].range.unit=percent",
            "pockethive.control-plane.worker.moderator.pattern.steps[0].range.start-pct=0",
            "pockethive.control-plane.worker.moderator.pattern.steps[0].range.end-pct=25",
            "pockethive.control-plane.worker.moderator.pattern.steps[0].mode=flat",
            "pockethive.control-plane.worker.moderator.pattern.steps[0].params.factor=0.6",
            "pockethive.control-plane.worker.moderator.pattern.steps[0].transition.type=none",
            "pockethive.control-plane.worker.moderator.pattern.steps[1].id=ramp",
            "pockethive.control-plane.worker.moderator.pattern.steps[1].range.unit=percent",
            "pockethive.control-plane.worker.moderator.pattern.steps[1].range.start-pct=25",
            "pockethive.control-plane.worker.moderator.pattern.steps[1].range.end-pct=50",
            "pockethive.control-plane.worker.moderator.pattern.steps[1].mode=ramp",
            "pockethive.control-plane.worker.moderator.pattern.steps[1].params.from=0.6",
            "pockethive.control-plane.worker.moderator.pattern.steps[1].params.to=1.0",
            "pockethive.control-plane.worker.moderator.pattern.steps[1].transition.type=none",
            "pockethive.control-plane.worker.moderator.pattern.steps[2].id=sinus",
            "pockethive.control-plane.worker.moderator.pattern.steps[2].range.unit=percent",
            "pockethive.control-plane.worker.moderator.pattern.steps[2].range.start-pct=50",
            "pockethive.control-plane.worker.moderator.pattern.steps[2].range.end-pct=75",
            "pockethive.control-plane.worker.moderator.pattern.steps[2].mode=sinus",
            "pockethive.control-plane.worker.moderator.pattern.steps[2].params.center=1.0",
            "pockethive.control-plane.worker.moderator.pattern.steps[2].params.amplitude=0.25",
            "pockethive.control-plane.worker.moderator.pattern.steps[2].params.cycles=1.0",
            "pockethive.control-plane.worker.moderator.pattern.steps[2].params.phase=0.0",
            "pockethive.control-plane.worker.moderator.pattern.steps[2].transition.type=none",
            "pockethive.control-plane.worker.moderator.pattern.steps[3].id=duty",
            "pockethive.control-plane.worker.moderator.pattern.steps[3].range.unit=percent",
            "pockethive.control-plane.worker.moderator.pattern.steps[3].range.start-pct=75",
            "pockethive.control-plane.worker.moderator.pattern.steps[3].range.end-pct=100",
            "pockethive.control-plane.worker.moderator.pattern.steps[3].mode=duty",
            "pockethive.control-plane.worker.moderator.pattern.steps[3].params.on-ms=30000",
            "pockethive.control-plane.worker.moderator.pattern.steps[3].params.off-ms=30000",
            "pockethive.control-plane.worker.moderator.pattern.steps[3].params.high=1.2",
            "pockethive.control-plane.worker.moderator.pattern.steps[3].params.low=0.4",
            "pockethive.control-plane.worker.moderator.pattern.steps[3].transition.type=none",
            "pockethive.control-plane.worker.moderator.normalization.enabled=true",
            "pockethive.control-plane.worker.moderator.normalization.tolerance-pct=5",
            "pockethive.control-plane.worker.moderator.jitter.type=none",
            "pockethive.control-plane.worker.moderator.seeds.default-seed=scenario/mod/default"
        )
        .run(context -> {
          ModeratorDefaults defaults = context.getBean(ModeratorDefaults.class);
          ModeratorWorkerConfig config = defaults.asConfig();

          assertThat(config.enabled()).isTrue();

          PatternConfig pattern = config.pattern();
          assertThat(pattern.duration()).isEqualTo(Duration.ofMinutes(4));
          assertThat(pattern.baseRateRps()).isEqualByComparingTo("50");

          RepeatConfig repeat = pattern.repeat();
          assertThat(repeat.enabled()).isTrue();
          assertThat(repeat.until()).isEqualTo(RepeatUntil.TOTAL_TIME);
          assertThat(repeat.align()).isEqualTo(RepeatAlignment.FROM_START);

          assertThat(config.jitter().type()).isEqualTo(JitterType.NONE);
          assertThat(config.normalization().enabled()).isTrue();
          assertThat(config.normalization().tolerancePct()).isEqualByComparingTo("5");

          List<StepConfig> steps = pattern.steps();
          assertThat(steps).hasSize(4);

          StepConfig flat = steps.get(0);
          assertStep(flat, "flat", StepMode.FLAT, BigDecimal.ZERO, BigDecimal.valueOf(25));
          assertParamEquals(flat, "factor", "0.6");
          assertThat(flat.transition().type()).isEqualTo(TransitionType.NONE);

          StepConfig ramp = steps.get(1);
          assertStep(ramp, "ramp", StepMode.RAMP, BigDecimal.valueOf(25), BigDecimal.valueOf(50));
          assertParamEquals(ramp, "from", "0.6");
          assertParamEquals(ramp, "to", "1.0");
          assertThat(ramp.transition().type()).isEqualTo(TransitionType.NONE);

          StepConfig sinus = steps.get(2);
          assertStep(sinus, "sinus", StepMode.SINUS, BigDecimal.valueOf(50), BigDecimal.valueOf(75));
          assertParamEquals(sinus, "center", "1.0");
          assertParamEquals(sinus, "amplitude", "0.25");
          assertParamEquals(sinus, "cycles", "1.0");
          assertParamEquals(sinus, "phase", "0.0");
          assertThat(sinus.transition().type()).isEqualTo(TransitionType.NONE);

          StepConfig duty = steps.get(3);
          assertStep(duty, "duty", StepMode.DUTY, BigDecimal.valueOf(75), BigDecimal.valueOf(100));
          assertParamEquals(duty, "onMs", "30000");
          assertParamEquals(duty, "offMs", "30000");
          assertParamEquals(duty, "high", "1.2");
          assertParamEquals(duty, "low", "0.4");
          assertThat(duty.transition().type()).isEqualTo(TransitionType.NONE);
        });
  }

  private static void assertStep(StepConfig step,
                                 String expectedId,
                                 StepMode expectedMode,
                                 BigDecimal expectedStart,
                                 BigDecimal expectedEnd) {
    assertThat(step.id()).isEqualTo(expectedId);
    assertThat(step.mode()).isEqualTo(expectedMode);

    StepRangeConfig range = step.range();
    assertThat(range.unit()).isEqualTo(StepRangeUnit.PERCENT);
    assertThat(range.startPct()).isEqualByComparingTo(expectedStart);
    assertThat(range.endPct()).isEqualByComparingTo(expectedEnd);
  }

  private static void assertParamEquals(StepConfig step, String key, String expected) {
    Object value = step.params().get(key);
    if (value == null) {
      String relaxed = key.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
      value = step.params().get(relaxed);
    }
    assertThat(value)
        .as("parameter '%s' should be present for step '%s'", key, step.id())
        .isNotNull();
    assertThat(new BigDecimal(value.toString())).isEqualByComparingTo(expected);
  }
}
