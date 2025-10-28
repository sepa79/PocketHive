package io.pockethive.moderator.shaper.runtime;

import io.pockethive.moderator.ModeratorWorkerConfig;
import io.pockethive.moderator.shaper.config.JitterConfig;
import io.pockethive.moderator.shaper.config.JitterType;
import io.pockethive.moderator.shaper.config.SeedsConfig;
import io.pockethive.moderator.shaper.config.TimeMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.SplittableRandom;

/**
 * Deterministic token bucket that uses the {@link PatternTimeline} multiplier to pace acknowledgements.
 */
public final class PatternAckPacer {

  private static final double MILLIS_PER_NANO = 1e-6;
  private static final double NANOS_PER_SECOND = 1_000_000_000d;
  private static final Duration MIN_SLEEP = Duration.ofMillis(1);

  private final Clock clock;
  private final Sleeper sleeper;
  private final PatternTimeline timeline;
  private final double baseRateRps;
  private final double warpFactor;
  private final TimeMode timeMode;
  private final SplittableRandom jitterRandom;
  private final double jitterMaxMillis;
  private final double jitterPeriodMillis;
  private final JitterType jitterType;
  private final double capacity;

  private PatternTimeline.TimelineSample lastSample;
  private Duration lastProfileElapsed;
  private double tokens;

  public PatternAckPacer(ModeratorWorkerConfig config, Clock clock) {
    this(config, clock, defaultSleeper());
  }

  public PatternAckPacer(ModeratorWorkerConfig config, Clock clock, Sleeper sleeper) {
    Objects.requireNonNull(config, "config");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    Instant runStart = clock.instant();
    this.timeline = new PatternTimeline(config, runStart);
    this.baseRateRps = config.pattern().baseRateRps().doubleValue();
    this.timeMode = config.time().mode();
    this.warpFactor = timeMode == TimeMode.WARP ? config.time().warpFactor().doubleValue() : 1d;
    this.capacity = Math.max(baseRateRps * 60d, 10d);

    JitterConfig jitter = config.jitter();
    this.jitterType = jitter.type();
    this.jitterMaxMillis = jitter.max() == null ? 0d : jitter.max().toNanos() * MILLIS_PER_NANO;
    this.jitterPeriodMillis = jitter.period() == null ? 0d : jitter.period().toNanos() * MILLIS_PER_NANO;
    this.jitterRandom = jitter.type() == JitterType.NONE
        ? null
        : new SplittableRandom(seedForJitter(config));

    this.lastSample = timeline.sample(runStart);
    this.lastProfileElapsed = lastSample.profileElapsed();
    this.tokens = 0d;
  }

  public AwaitResult awaitReady() throws InterruptedException {
    Duration waited = Duration.ZERO;
    while (true) {
      Instant now = clock.instant();
      PatternTimeline.TimelineSample sample = timeline.sample(now);
      advance(sample);
      if (tokens >= 1d) {
        tokens -= 1d;
        Duration jitterDelay = applyJitter(sample);
        if (!jitterDelay.isZero() && !jitterDelay.isNegative()) {
          sleeper.sleep(jitterDelay);
          waited = waited.plus(jitterDelay);
        }
        Instant readyAt = clock.instant();
        double targetRps = baseRateRps * sample.multiplier();
        double bucketLevel = Math.max(tokens, 0d);
        return new AwaitResult(sample, targetRps, bucketLevel, waited, jitterDelay, readyAt);
      }

      Duration wait = computeWait(sample);
      if (wait.isZero() || wait.isNegative()) {
        wait = MIN_SLEEP;
      }
      sleeper.sleep(wait);
      waited = waited.plus(wait);
    }
  }

  private void advance(PatternTimeline.TimelineSample current) {
    double deltaSeconds = secondsBetween(lastProfileElapsed, current.profileElapsed());
    if (deltaSeconds > 0d) {
      double prevRate = baseRateRps * lastSample.multiplier();
      double currentRate = baseRateRps * current.multiplier();
      double avgRate = (prevRate + currentRate) / 2d;
      tokens = Math.min(tokens + avgRate * deltaSeconds, capacity);
    }
    lastSample = current;
    lastProfileElapsed = current.profileElapsed();
  }

  private Duration computeWait(PatternTimeline.TimelineSample sample) {
    double targetRps = baseRateRps * sample.multiplier();
    if (targetRps <= 0d) {
      return MIN_SLEEP;
    }
    double missing = Math.max(0d, 1d - tokens);
    double profileSeconds = missing / targetRps;
    double realSeconds = timeMode == TimeMode.WARP ? profileSeconds / Math.max(warpFactor, 1e-9) : profileSeconds;
    long nanos = (long) Math.ceil(realSeconds * NANOS_PER_SECOND);
    if (nanos <= 0L) {
      return Duration.ZERO;
    }
    return Duration.ofNanos(nanos);
  }

  private Duration applyJitter(PatternTimeline.TimelineSample sample) {
    if (jitterRandom == null || jitterMaxMillis <= 0d) {
      return Duration.ZERO;
    }
    double draw = jitterRandom.nextDouble();
    double millis;
    if (jitterType == JitterType.SEQUENCE || jitterPeriodMillis <= 0d) {
      millis = jitterMaxMillis * draw;
    } else {
      double profileMillis = sample.profileElapsed().toNanos() * MILLIS_PER_NANO;
      double ratio = (profileMillis % jitterPeriodMillis) / jitterPeriodMillis;
      double envelope = 0.5d + 0.5d * Math.sin(2d * Math.PI * ratio);
      millis = jitterMaxMillis * draw * envelope;
    }
    long nanos = (long) Math.max(0d, Math.round(millis * 1_000_000d));
    return Duration.ofNanos(nanos);
  }

  public static Sleeper defaultSleeper() {
    return duration -> {
      if (duration == null || duration.isNegative() || duration.isZero()) {
        return;
      }
      long millis = duration.toMillis();
      int nanos = (int) duration.minusMillis(millis).toNanos();
      Thread.sleep(millis, nanos);
    };
  }

  private static long seedForJitter(ModeratorWorkerConfig config) {
    JitterConfig jitter = config.jitter();
    SeedsConfig seeds = config.seeds();
    String source = jitter.seed();
    if (isBlank(source)) {
      source = lookup(seeds, "jitter");
    }
    if (isBlank(source)) {
      source = seeds.defaultSeed();
    }
    if (isBlank(source)) {
      source = "moderator:jitter";
    }
    return toSeed(source);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static String lookup(SeedsConfig seeds, String key) {
    Map<String, String> overrides = seeds.overrides();
    if (overrides == null) {
      return null;
    }
    return overrides.get(key);
  }

  private static long toSeed(String text) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
      return ByteBuffer.wrap(hash).getLong();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private static double secondsBetween(Duration previous, Duration current) {
    if (current == null || previous == null) {
      return 0d;
    }
    long deltaNanos = current.toNanos() - previous.toNanos();
    if (deltaNanos <= 0L) {
      return 0d;
    }
    return deltaNanos / NANOS_PER_SECOND;
  }

  public double patternDurationMillis() {
    return timeline.patternDurationMillis();
  }

  public record AwaitResult(PatternTimeline.TimelineSample sample,
                            double targetRps,
                            double bucketLevel,
                            Duration waitDuration,
                            Duration jitterDuration,
                            Instant readyAt) {

    public Duration totalDelay() {
      return waitDuration.plus(jitterDuration);
    }
  }

  @FunctionalInterface
  public interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
  }
}

