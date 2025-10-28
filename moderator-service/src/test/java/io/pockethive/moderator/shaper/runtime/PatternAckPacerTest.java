package io.pockethive.moderator.shaper.runtime;

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
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PatternAckPacerTest {

  private MutableClock clock;
  private PatternAckPacer.Sleeper sleeper;

  @BeforeEach
  void setUp() {
    clock = new MutableClock(Instant.parse("2025-01-01T00:00:00Z"), ZoneId.of("UTC"));
    sleeper = duration -> clock.advance(duration);
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

    private void advance(Duration duration) {
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

