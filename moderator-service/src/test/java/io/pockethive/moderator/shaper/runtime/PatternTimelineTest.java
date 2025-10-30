package io.pockethive.moderator.shaper.runtime;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.pockethive.moderator.ModeratorWorkerConfig;
import io.pockethive.moderator.shaper.config.GlobalMutatorConfig;
import io.pockethive.moderator.shaper.config.JitterConfig;
import io.pockethive.moderator.shaper.config.NormalizationConfig;
import io.pockethive.moderator.shaper.config.PatternConfig;
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
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PatternTimelineTest {

  private static final ZoneId UTC = ZoneId.of("UTC");
  private static final Instant RUN_START = Instant.parse("2024-01-01T00:00:00Z");
  private static final RepeatConfig DEFAULT_REPEAT =
      new RepeatConfig(true, RepeatUntil.TOTAL_TIME, null, RepeatAlignment.FROM_START);
  private static final SeedsConfig DEFAULT_SEEDS = new SeedsConfig("ph/test/default", Map.of());
  private static final TimeConfig REALTIME = new TimeConfig(TimeMode.REALTIME, BigDecimal.ONE, UTC);

  @Test
  void flatStepProducesConstantMultiplier() {
    PatternConfig pattern = new PatternConfig(
        Duration.ofHours(1),
        BigDecimal.valueOf(1000),
        DEFAULT_REPEAT,
        List.of(new StepConfig(
            "flat",
            percentRange(0, 100),
            StepMode.FLAT,
            Map.of("factor", 1.2d),
            List.of(),
            TransitionConfig.none())));
    ModeratorWorkerConfig config = new ModeratorWorkerConfig(
        true,
        REALTIME,
        new RunConfig(Duration.ofHours(6)),
        pattern,
        NormalizationConfig.disabled(),
        List.of(),
        JitterConfig.disabled(),
        DEFAULT_SEEDS);

    PatternTimeline timeline = new PatternTimeline(config, RUN_START);

    PatternTimeline.TimelineSample start = timeline.sample(RUN_START);
    PatternTimeline.TimelineSample mid = timeline.sample(RUN_START.plus(Duration.ofMinutes(30)));

    assertThat(start.multiplier()).isCloseTo(1.2d, within(1e-6));
    assertThat(mid.multiplier()).isCloseTo(1.2d, within(1e-6));
    assertThat(timeline.normalizationConstant()).isEqualTo(1d);
  }

  @Test
  void smoothTransitionBlendsSteps() {
    StepConfig step1 = new StepConfig(
        "morning",
        percentRange(0, 50),
        StepMode.RAMP,
        Map.of("from", 0.5d, "to", 1.0d),
        List.of(),
        new TransitionConfig(TransitionType.SMOOTH, null, BigDecimal.TEN));
    StepConfig step2 = new StepConfig(
        "evening",
        percentRange(50, 100),
        StepMode.FLAT,
        Map.of("factor", 0.2d),
        List.of(),
        TransitionConfig.none());
    PatternConfig pattern = new PatternConfig(
        Duration.ofMinutes(10),
        BigDecimal.valueOf(1000),
        DEFAULT_REPEAT,
        List.of(step1, step2));
    ModeratorWorkerConfig config = new ModeratorWorkerConfig(
        true,
        REALTIME,
        new RunConfig(Duration.ofHours(1)),
        pattern,
        NormalizationConfig.disabled(),
        List.of(),
        JitterConfig.disabled(),
        DEFAULT_SEEDS);
    PatternTimeline timeline = new PatternTimeline(config, RUN_START);

    PatternTimeline.TimelineSample sample = timeline.sample(Duration.ofSeconds(285));

    assertThat(sample.multiplier()).isCloseTo(0.5875d, within(1e-4));
  }

  @Test
  void normalizationAdjustsMeanAcrossCycle() {
    StepConfig low = new StepConfig(
        "low",
        percentRange(0, 50),
        StepMode.FLAT,
        Map.of("factor", 0.2d),
        List.of(),
        TransitionConfig.none());
    StepConfig high = new StepConfig(
        "high",
        percentRange(50, 100),
        StepMode.FLAT,
        Map.of("factor", 2.0d),
        List.of(),
        TransitionConfig.none());
    PatternConfig pattern = new PatternConfig(
        Duration.ofHours(1),
        BigDecimal.valueOf(1000),
        DEFAULT_REPEAT,
        List.of(low, high));
    NormalizationConfig normalization = new NormalizationConfig(true, BigDecimal.valueOf(100));
    ModeratorWorkerConfig config = new ModeratorWorkerConfig(
        true,
        REALTIME,
        new RunConfig(Duration.ofHours(6)),
        pattern,
        normalization,
        List.of(),
        JitterConfig.disabled(),
        DEFAULT_SEEDS);

    PatternTimeline timeline = new PatternTimeline(config, RUN_START);

    double expectedConstant = 1d / 1.1d;
    assertThat(timeline.normalizationConstant()).isCloseTo(expectedConstant, within(1e-3));
    PatternTimeline.TimelineSample sample = timeline.sample(Duration.ofMinutes(15));
    assertThat(sample.multiplier()).isCloseTo(0.2d * expectedConstant, within(1e-4));
  }

  @Test
  void burstMutatorProducesLiftDuringWindow() {
    StepMutatorConfig burst = new StepMutatorConfig(
        "burst",
        Map.of(
            "liftPct", 50,
            "durationMs", Map.of("min", 60000, "max", 60000),
            "everyMs", Map.of("min", 300000, "max", 300000),
            "jitterStartMs", 0,
            "seed", "ph/test/burst"));
    StepConfig step = new StepConfig(
        "day",
        percentRange(0, 100),
        StepMode.FLAT,
        Map.of("factor", 1.0d),
        List.of(burst),
        TransitionConfig.none());
    PatternConfig pattern = new PatternConfig(
        Duration.ofHours(1),
        BigDecimal.valueOf(1000),
        DEFAULT_REPEAT,
        List.of(step));
    ModeratorWorkerConfig config = new ModeratorWorkerConfig(
        true,
        REALTIME,
        new RunConfig(Duration.ofHours(6)),
        pattern,
        NormalizationConfig.disabled(),
        List.of(),
        JitterConfig.disabled(),
        DEFAULT_SEEDS);

    PatternTimeline timeline = new PatternTimeline(config, RUN_START);

    PatternTimeline.TimelineSample insideBurst = timeline.sample(Duration.ofSeconds(30));
    PatternTimeline.TimelineSample outsideBurst = timeline.sample(Duration.ofMinutes(2));

    assertThat(insideBurst.multiplier()).isCloseTo(1.5d, within(1e-4));
    assertThat(outsideBurst.multiplier()).isCloseTo(1.0d, within(1e-4));
  }

  @Test
  void calendarAlignmentResetsAtMidnight() {
    StepConfig morning = new StepConfig(
        "morning",
        clockRange("00:00", "12:00"),
        StepMode.FLAT,
        Map.of("factor", 0.5d),
        List.of(),
        TransitionConfig.none());
    StepConfig evening = new StepConfig(
        "evening",
        clockRange("12:00", "24:00"),
        StepMode.FLAT,
        Map.of("factor", 2.0d),
        List.of(),
        TransitionConfig.none());
    PatternConfig pattern = new PatternConfig(
        Duration.ofHours(24),
        BigDecimal.valueOf(1000),
        new RepeatConfig(true, RepeatUntil.TOTAL_TIME, null, RepeatAlignment.CALENDAR),
        List.of(morning, evening));
    ModeratorWorkerConfig config = new ModeratorWorkerConfig(
        true,
        REALTIME,
        new RunConfig(Duration.ofDays(2)),
        pattern,
        NormalizationConfig.disabled(),
        List.of(),
        JitterConfig.disabled(),
        DEFAULT_SEEDS);
    Instant runStart = Instant.parse("2024-01-01T10:00:00Z");
    PatternTimeline timeline = new PatternTimeline(config, runStart);

    PatternTimeline.TimelineSample initial = timeline.sample(Duration.ofHours(1));
    PatternTimeline.TimelineSample afternoon = timeline.sample(Duration.ofHours(5));
    PatternTimeline.TimelineSample nextMorning = timeline.sample(Duration.ofHours(15));

    assertThat(initial.multiplier()).isCloseTo(0.5d, within(1e-4));
    assertThat(afternoon.multiplier()).isCloseTo(2.0d, within(1e-4));
    assertThat(nextMorning.multiplier()).isCloseTo(0.5d, within(1e-4));
  }

  @Test
  void spikeGlobalMutatorAppliesLiftWithinWindow() {
    StepConfig step = new StepConfig(
        "flat",
        percentRange(0, 100),
        StepMode.FLAT,
        Map.of("factor", 1.0d),
        List.of(),
        TransitionConfig.none());
    GlobalMutatorConfig spike = new GlobalMutatorConfig(
        "spike",
        Map.of("at", "00:30", "width", "10m", "liftPct", 50));
    PatternConfig pattern = new PatternConfig(
        Duration.ofHours(1),
        BigDecimal.valueOf(1000),
        DEFAULT_REPEAT,
        List.of(step));
    ModeratorWorkerConfig config = new ModeratorWorkerConfig(
        true,
        REALTIME,
        new RunConfig(Duration.ofHours(2)),
        pattern,
        NormalizationConfig.disabled(),
        List.of(spike),
        JitterConfig.disabled(),
        DEFAULT_SEEDS);

    PatternTimeline timeline = new PatternTimeline(config, RUN_START);

    PatternTimeline.TimelineSample lifted = timeline.sample(Duration.ofMinutes(30));
    PatternTimeline.TimelineSample normal = timeline.sample(Duration.ofMinutes(50));

    assertThat(lifted.multiplier()).isCloseTo(1.5d, within(1e-4));
    assertThat(normal.multiplier()).isCloseTo(1.0d, within(1e-4));
  }

  @Test
  void logsWarningWhenNormalizationToleranceExceeded() {
    Logger logger = (Logger) LoggerFactory.getLogger(PatternTimeline.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      StepConfig step = new StepConfig(
          "flat",
          percentRange(0, 100),
          StepMode.FLAT,
          Map.of("factor", 2.0d),
          List.of(),
          TransitionConfig.none());
      PatternConfig pattern = new PatternConfig(
          Duration.ofHours(1),
          BigDecimal.valueOf(1000),
          DEFAULT_REPEAT,
          List.of(step));
      NormalizationConfig normalization = new NormalizationConfig(true, BigDecimal.valueOf(5));
      ModeratorWorkerConfig config = new ModeratorWorkerConfig(
          true,
          REALTIME,
          new RunConfig(Duration.ofHours(2)),
          pattern,
          normalization,
          List.of(),
          JitterConfig.disabled(),
          DEFAULT_SEEDS);

      new PatternTimeline(config, RUN_START);

      assertThat(appender.list)
          .anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("Normalization constant");
          });
    } finally {
      logger.detachAppender(appender);
    }
  }

  private static StepRangeConfig percentRange(int start, int end) {
    return new StepRangeConfig(StepRangeUnit.PERCENT, null, null, BigDecimal.valueOf(start), BigDecimal.valueOf(end));
  }

  private static StepRangeConfig clockRange(String start, String end) {
    return new StepRangeConfig(StepRangeUnit.CLOCK, start, end, null, null);
  }
}
