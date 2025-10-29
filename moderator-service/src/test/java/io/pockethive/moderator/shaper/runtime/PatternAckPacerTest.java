package io.pockethive.moderator.shaper.runtime;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.pockethive.moderator.ModeratorWorkerConfig;
import io.pockethive.moderator.shaper.config.JitterConfig;
import io.pockethive.moderator.shaper.config.JitterType;
import io.pockethive.moderator.shaper.config.NormalizationConfig;
import io.pockethive.moderator.shaper.config.PatternConfig;
import io.pockethive.moderator.shaper.config.RepeatAlignment;
import io.pockethive.moderator.shaper.config.RepeatConfig;
import io.pockethive.moderator.shaper.config.RepeatUntil;
import io.pockethive.moderator.shaper.config.RunConfig;
import io.pockethive.moderator.shaper.config.SeedsConfig;
import io.pockethive.moderator.shaper.config.StepConfig;
import io.pockethive.moderator.shaper.config.StepMode;
import io.pockethive.moderator.shaper.config.StepRangeConfig;
import io.pockethive.moderator.shaper.config.StepRangeUnit;
import io.pockethive.moderator.shaper.config.TimeConfig;
import io.pockethive.moderator.shaper.config.TimeMode;
import io.pockethive.moderator.shaper.config.TransitionConfig;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PatternAckPacerTest {

  private MutableClock clock;
  private PatternAckPacer.Sleeper sleeper;

  @BeforeEach
  void setUp() {
    clock = new MutableClock(Instant.parse("2025-01-01T00:00:00Z"), ZoneId.of("UTC"));
    sleeper = duration -> clock.advance(duration);
  }

  @Test
  void logsStepTransitionsOnFirstTick() throws Exception {
    Logger logger = (Logger) LoggerFactory.getLogger(PatternAckPacer.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      ModeratorWorkerConfig config = simpleStepsScenarioConfig();
      PatternAckPacer pacer = new PatternAckPacer(config, clock, sleeper);

      Instant start = clock.instant();
      List<Duration> offsets = List.of(
          Duration.ofSeconds(30),
          Duration.ofSeconds(105),
          Duration.ofSeconds(135),
          Duration.ofSeconds(195));

      for (Duration offset : offsets) {
        advanceTo(start.plus(offset));
        pacer.awaitReady();
      }

      assertThat(appender.list).hasSizeGreaterThanOrEqualTo(4);
      List<String> messages = appender.list.stream()
          .map(ILoggingEvent::getFormattedMessage)
          .toList();
      assertThat(messages).anySatisfy(msg -> assertThat(msg)
          .contains("stepId=flat")
          .contains("multiplier=")
          .contains("targetRps="));
      assertThat(messages).anySatisfy(msg -> assertThat(msg).contains("stepId=ramp"));
      assertThat(messages).anySatisfy(msg -> assertThat(msg).contains("stepId=sinus"));
      assertThat(messages).anySatisfy(msg -> assertThat(msg).contains("stepId=duty"));
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void waitsUntilTokenAvailable() throws Exception {
    ModeratorWorkerConfig config = config(JitterConfig.disabled());
    PatternAckPacer pacer = new PatternAckPacer(config, clock, sleeper);

    PatternAckPacer.AwaitResult first = pacer.awaitReady();

    assertThat(first.waitDuration()).isEqualTo(Duration.ofMillis(100));
    assertThat(first.jitterDuration()).isEqualTo(Duration.ZERO);
    assertThat(first.totalDelay()).isEqualTo(Duration.ofMillis(100));
    assertThat(first.bucketLevel()).isBetween(0d, 0.5d);
  }

  @Test
  void sequenceJitterUsesDeterministicSeed() throws Exception {
    Duration max = Duration.ofMillis(40);
    JitterConfig jitter = new JitterConfig(JitterType.SEQUENCE, max, "seq-seed", null);
    ModeratorWorkerConfig config = config(jitter);
    PatternAckPacer pacer = new PatternAckPacer(config, clock, sleeper);

    PatternAckPacer.AwaitResult result = pacer.awaitReady();

    double expectedMillis = max.toMillis() * nextDraw("seq-seed");
    long expectedNanos = Math.round(expectedMillis * 1_000_000d);
    assertThat(result.jitterDuration().toNanos()).isEqualTo(expectedNanos);
  }

  @Test
  void periodicJitterFollowsEnvelope() throws Exception {
    Duration max = Duration.ofMillis(50);
    Duration period = Duration.ofSeconds(1);
    JitterConfig jitter = new JitterConfig(JitterType.PERIODIC, max, "per-seed", period);
    ModeratorWorkerConfig config = config(jitter);
    PatternAckPacer pacer = new PatternAckPacer(config, clock, sleeper);

    PatternAckPacer.AwaitResult result = pacer.awaitReady();

    double profileMillis = result.sample().profileElapsed().toNanos() * 1e-6;
    double ratio = (profileMillis % period.toMillis()) / period.toMillis();
    double envelope = 0.5d + 0.5d * Math.sin(2d * Math.PI * ratio);
    double expectedMillis = max.toMillis() * nextDraw("per-seed") * envelope;
    long expectedNanos = Math.round(expectedMillis * 1_000_000d);
    assertThat(result.jitterDuration().toNanos()).isEqualTo(expectedNanos);
  }

  @Test
  void scenarioSimpleStepsAdvancesThroughPhases() throws Exception {
    ModeratorWorkerConfig config = simpleStepsScenarioConfig();
    PatternAckPacer pacer = new PatternAckPacer(config, clock, sleeper);

    Instant start = clock.instant();
    List<Duration> offsets = List.of(
        Duration.ofSeconds(30),
        Duration.ofSeconds(105),
        Duration.ofSeconds(135),
        Duration.ofSeconds(195));

    List<PatternAckPacer.AwaitResult> results = new ArrayList<>();
    for (Duration offset : offsets) {
      advanceTo(start.plus(offset));
      results.add(pacer.awaitReady());
    }

    assertThat(results)
        .extracting(result -> result.sample().stepId())
        .containsExactly("flat", "ramp", "sinus", "duty");

    double normalization = results.get(0).sample().normalizationConstant();
    assertThat(normalization).isCloseTo(1.25d, within(1e-6));

    double[] rawMultipliers = {0.6d, 0.9d, 1.25d, 1.2d};
    double baseRate = config.pattern().baseRateRps().doubleValue();

    for (int i = 0; i < results.size(); i++) {
      PatternAckPacer.AwaitResult result = results.get(i);
      double expectedMultiplier = rawMultipliers[i] * normalization;
      assertThat(result.sample().multiplier()).isCloseTo(expectedMultiplier, within(1e-6));
      double expectedTarget = expectedMultiplier * baseRate;
      assertThat(result.targetRps()).isCloseTo(expectedTarget, within(1e-6));
      assertThat(result.targetRps()).isNotCloseTo(baseRate, within(1e-6));
    }
  }

  private ModeratorWorkerConfig config(JitterConfig jitter) {
    TimeConfig time = new TimeConfig(TimeMode.REALTIME, BigDecimal.ONE, ZoneId.of("UTC"));
    RunConfig run = new RunConfig(Duration.ofSeconds(30));
    RepeatConfig repeat = new RepeatConfig(false, RepeatUntil.TOTAL_TIME, null, RepeatAlignment.FROM_START);
    StepRangeConfig range = new StepRangeConfig(
        StepRangeUnit.PERCENT,
        null,
        null,
        BigDecimal.ZERO,
        BigDecimal.valueOf(100));
    StepConfig step = new StepConfig(
        "all",
        range,
        StepMode.FLAT,
        Map.of("factor", BigDecimal.ONE),
        List.of(),
        TransitionConfig.none());
    PatternConfig pattern = new PatternConfig(
        Duration.ofSeconds(10),
        BigDecimal.valueOf(10),
        repeat,
        List.of(step));
    return new ModeratorWorkerConfig(
        true,
        time,
        run,
        pattern,
        NormalizationConfig.disabled(),
        List.of(),
        jitter,
        SeedsConfig.empty());
  }

  private ModeratorWorkerConfig simpleStepsScenarioConfig() {
    TimeConfig time = new TimeConfig(TimeMode.WARP, BigDecimal.ONE, ZoneId.of("UTC"));
    RunConfig run = new RunConfig(Duration.ofHours(1));
    RepeatConfig repeat = new RepeatConfig(true, RepeatUntil.TOTAL_TIME, null, RepeatAlignment.FROM_START);

    StepRangeConfig flatRange = new StepRangeConfig(
        StepRangeUnit.PERCENT,
        null,
        null,
        BigDecimal.ZERO,
        BigDecimal.valueOf(25));
    StepConfig flat = new StepConfig(
        "flat",
        flatRange,
        StepMode.FLAT,
        Map.of("factor", BigDecimal.valueOf(0.6)),
        List.of(),
        TransitionConfig.none());

    StepRangeConfig rampRange = new StepRangeConfig(
        StepRangeUnit.PERCENT,
        null,
        null,
        BigDecimal.valueOf(25),
        BigDecimal.valueOf(50));
    StepConfig ramp = new StepConfig(
        "ramp",
        rampRange,
        StepMode.RAMP,
        Map.of(
            "from", BigDecimal.valueOf(0.6),
            "to", BigDecimal.ONE),
        List.of(),
        TransitionConfig.none());

    StepRangeConfig sinusRange = new StepRangeConfig(
        StepRangeUnit.PERCENT,
        null,
        null,
        BigDecimal.valueOf(50),
        BigDecimal.valueOf(75));
    StepConfig sinus = new StepConfig(
        "sinus",
        sinusRange,
        StepMode.SINUS,
        Map.of(
            "center", BigDecimal.ONE,
            "amplitude", BigDecimal.valueOf(0.25),
            "cycles", BigDecimal.ONE,
            "phase", BigDecimal.ZERO),
        List.of(),
        TransitionConfig.none());

    StepRangeConfig dutyRange = new StepRangeConfig(
        StepRangeUnit.PERCENT,
        null,
        null,
        BigDecimal.valueOf(75),
        BigDecimal.valueOf(100));
    StepConfig duty = new StepConfig(
        "duty",
        dutyRange,
        StepMode.DUTY,
        Map.of(
            "onMs", BigDecimal.valueOf(30_000),
            "offMs", BigDecimal.valueOf(30_000),
            "high", BigDecimal.valueOf(1.2),
            "low", BigDecimal.valueOf(0.4)),
        List.of(),
        TransitionConfig.none());

    PatternConfig pattern = new PatternConfig(
        Duration.ofMinutes(4),
        BigDecimal.valueOf(50),
        repeat,
        List.of(flat, ramp, sinus, duty));

    return new ModeratorWorkerConfig(
        true,
        time,
        run,
        pattern,
        new NormalizationConfig(true, BigDecimal.valueOf(5)),
        List.of(),
        JitterConfig.disabled(),
        new SeedsConfig("scenario/mod/default", Map.of()));
  }

  private void advanceTo(Instant target) {
    Instant now = clock.instant();
    if (target.isAfter(now)) {
      clock.advance(Duration.between(now, target));
    }
  }

  private double nextDraw(String seed) {
    long value = toSeed(seed);
    return new SplittableRandom(value).nextDouble();
  }

  private static long toSeed(String text) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
      return ByteBuffer.wrap(hash).getLong();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static final class MutableClock extends Clock {

    private Instant current;
    private final ZoneId zone;

    private MutableClock(Instant current, ZoneId zone) {
      this.current = current;
      this.zone = zone;
    }

    void advance(Duration duration) {
      if (duration == null || duration.isNegative()) {
        return;
      }
      current = current.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return new MutableClock(current, zone);
    }

    @Override
    public Instant instant() {
      return current;
    }
  }
}

