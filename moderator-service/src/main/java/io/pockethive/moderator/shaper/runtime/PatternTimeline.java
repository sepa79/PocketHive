package io.pockethive.moderator.shaper.runtime;

import io.pockethive.moderator.ModeratorWorkerConfig;
import io.pockethive.moderator.shaper.config.GlobalMutatorConfig;
import io.pockethive.moderator.shaper.config.NormalizationConfig;
import io.pockethive.moderator.shaper.config.PatternConfig;
import io.pockethive.moderator.shaper.config.RepeatAlignment;
import io.pockethive.moderator.shaper.config.RepeatConfig;
import io.pockethive.moderator.shaper.config.RepeatUntil;
import io.pockethive.moderator.shaper.config.SeedsConfig;
import io.pockethive.moderator.shaper.config.StepConfig;
import io.pockethive.moderator.shaper.config.StepMode;
import io.pockethive.moderator.shaper.config.StepMutatorConfig;
import io.pockethive.moderator.shaper.config.StepRangeConfig;
import io.pockethive.moderator.shaper.config.TimeConfig;
import io.pockethive.moderator.shaper.config.TimeMode;
import io.pockethive.moderator.shaper.config.TransitionConfig;
import io.pockethive.moderator.shaper.config.TransitionType;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.SplittableRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.convert.DurationStyle;

/**
 * Converts the bound moderator shaper configuration into a deterministic multiplier timeline. The
 * timeline exposes the instantaneous multiplier and normalization constant for any profile time so
 * that the pacing logic can schedule acknowledgements without re-reading the configuration graph.
 */
public final class PatternTimeline {

  private static final Logger log = LoggerFactory.getLogger(PatternTimeline.class);
  private static final BigDecimal NANOS_PER_SECOND = BigDecimal.valueOf(1_000_000_000L);
  private static final double MILLIS_PER_NANO = 1e-6;
  private static final double EPSILON = 1e-9;

  private final Instant runStart;
  private final TimeConfig time;
  private final PatternConfig pattern;
  private final RepeatConfig repeat;
  private final NormalizationConfig normalization;
  private final Duration patternDuration;
  private final double patternDurationMillis;
  private final List<StepSegment> steps;
  private final List<GlobalMutator> globalMutators;
  private final double normalizationConstant;

  public PatternTimeline(ModeratorWorkerConfig config, Instant runStart) {
    this.runStart = Objects.requireNonNull(runStart, "runStart");
    Objects.requireNonNull(config, "config");
    this.time = config.time();
    this.pattern = config.pattern();
    this.repeat = pattern.repeat();
    this.normalization = config.normalization();
    this.patternDuration = pattern.duration();
    this.patternDurationMillis = patternDuration.toNanos() * MILLIS_PER_NANO;

    Seeds seeds = new Seeds(config.seeds());
    this.steps = buildSegments(pattern, patternDuration, seeds);
    this.globalMutators = buildGlobalMutators(config.globalMutators(), patternDuration);
    this.normalizationConstant = computeNormalizationConstant(normalization, steps, patternDurationMillis);
  }

  public TimelineSample sample(Instant now) {
    Duration profileElapsed = toProfileElapsed(now);
    Instant profileInstant = runStart.plus(profileElapsed);
    double patternMillis = resolvePatternMillis(profileElapsed, profileInstant);
    double raw = rawMultiplierAt(patternMillis);
    double normalizedValue = raw * normalizationConstant;
    double finalValue = applyGlobalMutators(normalizedValue, patternMillis);
    return new TimelineSample(finalValue, normalizationConstant);
  }

  public TimelineSample sample(Duration profileElapsed) {
    Instant profileInstant = runStart.plus(profileElapsed);
    double patternMillis = resolvePatternMillis(profileElapsed, profileInstant);
    double raw = rawMultiplierAt(patternMillis);
    double normalizedValue = raw * normalizationConstant;
    double finalValue = applyGlobalMutators(normalizedValue, patternMillis);
    return new TimelineSample(finalValue, normalizationConstant);
  }

  public double normalizationConstant() {
    return normalizationConstant;
  }

  private Duration toProfileElapsed(Instant now) {
    Duration realElapsed = Duration.between(runStart, now);
    if (realElapsed.isNegative()) {
      realElapsed = Duration.ZERO;
    }
    if (time.mode() == TimeMode.REALTIME) {
      return realElapsed;
    }
    BigDecimal warp = time.warpFactor();
    BigDecimal nanos = BigDecimal.valueOf(realElapsed.toNanos());
    BigDecimal scaled = nanos.multiply(warp);
    BigDecimal[] parts = scaled.divideAndRemainder(NANOS_PER_SECOND);
    long seconds = parts[0].longValue();
    int nanosPart = parts[1].intValue();
    if (nanosPart < 0) {
      seconds -= 1;
      nanosPart += NANOS_PER_SECOND.intValueExact();
    }
    return Duration.ofSeconds(seconds, nanosPart);
  }

  private double resolvePatternMillis(Duration profileElapsed, Instant profileInstant) {
    double profileMillis = profileElapsed.toNanos() * MILLIS_PER_NANO;
    if (!repeat.enabled()) {
      return clampToPattern(profileMillis);
    }

    double fromStart = wrapFromStart(profileMillis);
    if (repeat.align() == RepeatAlignment.CALENDAR) {
      double calendarMillis = wrapCalendar(profileInstant, profileMillis);
      double limited = enforceOccurrenceLimit(profileMillis, calendarMillis);
      return limited;
    }
    return enforceOccurrenceLimit(profileMillis, fromStart);
  }

  private double enforceOccurrenceLimit(double profileMillis, double candidate) {
    if (repeat.until() == RepeatUntil.OCCURRENCES && repeat.occurrences() != null) {
      double totalDuration = repeat.occurrences() * patternDurationMillis;
      if (profileMillis >= totalDuration) {
        return Math.nextAfter(patternDurationMillis, Double.NEGATIVE_INFINITY);
      }
    }
    return candidate;
  }

  private double wrapFromStart(double profileMillis) {
    double cycles = Math.floor(profileMillis / patternDurationMillis);
    double wrapped = profileMillis - cycles * patternDurationMillis;
    if (wrapped < 0) {
      wrapped += patternDurationMillis;
    }
    if (wrapped >= patternDurationMillis) {
      wrapped = Math.nextAfter(patternDurationMillis, Double.NEGATIVE_INFINITY);
    }
    return wrapped;
  }

  private double wrapCalendar(Instant profileInstant, double profileMillis) {
    ZonedDateTime zoned = profileInstant.atZone(time.tz());
    Duration offset;
    if (patternDuration.equals(Duration.ofDays(1))) {
      ZonedDateTime start = zoned.truncatedTo(ChronoUnit.DAYS);
      offset = Duration.between(start, zoned);
    } else if (patternDuration.equals(Duration.ofDays(7))) {
      ZonedDateTime start = zoned
          .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
          .truncatedTo(ChronoUnit.DAYS);
      offset = Duration.between(start, zoned);
    } else {
      return wrapFromStart(profileMillis);
    }
    double millis = offset.toNanos() * MILLIS_PER_NANO;
    if (millis >= patternDurationMillis) {
      millis = wrapFromStart(millis);
    }
    return millis;
  }

  private double clampToPattern(double profileMillis) {
    if (profileMillis <= 0) {
      return 0d;
    }
    double max = Math.nextAfter(patternDurationMillis, Double.NEGATIVE_INFINITY);
    return Math.min(profileMillis, max);
  }

  private double rawMultiplierAt(double patternMillis) {
    double target = Math.min(Math.max(patternMillis, 0d), Math.nextAfter(patternDurationMillis, Double.NEGATIVE_INFINITY));
    for (int i = 0; i < steps.size(); i++) {
      StepSegment segment = steps.get(i);
      boolean last = i == steps.size() - 1;
      if (segment.contains(target, last)) {
        return segment.valueAt(target);
      }
    }
    return steps.get(steps.size() - 1).valueAt(target);
  }

  private double applyGlobalMutators(double value, double patternMillis) {
    double result = value;
    for (GlobalMutator mutator : globalMutators) {
      result = mutator.apply(result, patternMillis, patternDurationMillis);
    }
    return result;
  }

  private double computeNormalizationConstant(NormalizationConfig config,
                                              List<StepSegment> segments,
                                              double durationMillis) {
    if (config == null || !config.enabled()) {
      return 1d;
    }
    int samples = Math.max(segments.size() * 256, 2048);
    double step = durationMillis / samples;
    double sum = 0d;
    for (int i = 0; i < samples; i++) {
      double time = (i + 0.5d) * step;
      sum += rawMultiplierAt(time);
    }
    double mean = sum / samples;
    if (mean <= 0d) {
      throw new IllegalStateException("normalization mean must be positive");
    }
    double constant = 1d / mean;
    double deltaPct = Math.abs(constant - 1d) * 100d;
    double tolerance = config.tolerancePct().doubleValue();
    if (deltaPct > tolerance + 1e-6) {
      log.warn("Normalization constant {} exceeds tolerance {}% (delta {}%)", constant, tolerance, deltaPct);
    }
    return constant;
  }

  private static List<StepSegment> buildSegments(PatternConfig pattern,
                                                 Duration duration,
                                                 Seeds seeds) {
    List<StepConfig> ordered = new ArrayList<>(pattern.steps());
    ordered.sort(Comparator.comparing(step -> step.range().startFraction(duration)));
    List<StepSegment> segments = new ArrayList<>(ordered.size());
    for (StepConfig step : ordered) {
      StepRangeConfig range = step.range();
      double startFraction = range.startFraction(duration);
      double endFraction = range.endFraction(duration);
      double startMillis = startFraction * duration.toNanos() * MILLIS_PER_NANO;
      double endMillis = endFraction * duration.toNanos() * MILLIS_PER_NANO;
      StepEvaluator evaluator = createEvaluator(step, endMillis - startMillis);
      List<StepMutator> mutators = createMutators(step, seeds, endMillis - startMillis);
      Transition transition = createTransition(step.transition(), range, duration, startMillis, endMillis);
      segments.add(new StepSegment(step.id(), startMillis, endMillis, evaluator, mutators, transition));
    }
    for (int i = 0; i < segments.size(); i++) {
      StepSegment current = segments.get(i);
      StepSegment next = segments.get((i + 1) % segments.size());
      current.setNext(next);
    }
    return List.copyOf(segments);
  }

  private static List<StepMutator> createMutators(StepConfig step,
                                                  Seeds seeds,
                                                  double stepDurationMillis) {
    if (step.mutators().isEmpty()) {
      return List.of();
    }
    List<StepMutator> mutators = new ArrayList<>();
    for (StepMutatorConfig config : step.mutators()) {
      Map<String, Object> settings = config.settings();
      String fallback = step.id() + ":" + config.type();
      long seed = seeds.forKey((String) settings.get("seed"), config.type(), fallback);
      StepMutator mutator = switch (config.type().toLowerCase(Locale.ROOT)) {
        case "cap" -> new CapMutator(settings);
        case "noise" -> new NoiseMutator(settings, seed, stepDurationMillis);
        case "burst" -> new BurstMutator(settings, seed, stepDurationMillis);
        default -> throw new IllegalArgumentException("unsupported step mutator type: " + config.type());
      };
      mutators.add(mutator);
    }
    return List.copyOf(mutators);
  }

  private static StepEvaluator createEvaluator(StepConfig step, double stepDurationMillis) {
    Map<String, Object> params = step.params();
    return switch (step.mode()) {
      case FLAT -> new FlatEvaluator(readDouble(params, "factor", 1d));
      case RAMP -> new RampEvaluator(readDouble(params, "from", 1d), readDouble(params, "to", 1d));
      case SINUS -> new SinusEvaluator(
          readDouble(params, "center", 1d),
          readDouble(params, "amplitude", 0d),
          readDouble(params, "cycles", 1d),
          readDouble(params, "phase", 0d));
      case DUTY -> new DutyEvaluator(
          readDouble(params, "onMs", stepDurationMillis),
          readDouble(params, "offMs", 0d),
          readDouble(params, "high", 1d),
          readDouble(params, "low", 0d),
          stepDurationMillis);
    };
  }

  private static Transition createTransition(TransitionConfig config,
                                             StepRangeConfig range,
                                             Duration patternDuration,
                                             double startMillis,
                                             double endMillis) {
    if (config.type() == TransitionType.NONE) {
      return Transition.none(endMillis);
    }
    double stepDurationMillis = endMillis - startMillis;
    double transitionMillis = 0d;
    if (config.percent() != null) {
      transitionMillis = stepDurationMillis * config.percent().doubleValue() / 100d;
    } else if (config.duration() != null) {
      transitionMillis = config.duration().toNanos() * MILLIS_PER_NANO;
    }
    transitionMillis = Math.min(transitionMillis, stepDurationMillis);
    double transitionStart = endMillis - transitionMillis;
    return new Transition(config.type(), transitionStart, transitionMillis);
  }

  private static List<GlobalMutator> buildGlobalMutators(List<GlobalMutatorConfig> configs,
                                                         Duration patternDuration) {
    if (configs.isEmpty()) {
      return List.of();
    }
    List<GlobalMutator> mutators = new ArrayList<>();
    for (GlobalMutatorConfig config : configs) {
      Map<String, Object> settings = config.settings();
      GlobalMutator mutator = switch (config.type().toLowerCase(Locale.ROOT)) {
        case "spike" -> new SpikeGlobalMutator(settings, patternDuration);
        default -> throw new IllegalArgumentException("unsupported global mutator type: " + config.type());
      };
      mutators.add(mutator);
    }
    return List.copyOf(mutators);
  }

  private static double readDouble(Map<String, Object> map, String key, double defaultValue) {
    Object value = map.get(key);
    if (value == null) {
      return defaultValue;
    }
    if (value instanceof Number number) {
      return number.doubleValue();
    }
    if (value instanceof String text) {
      return Double.parseDouble(text);
    }
    throw new IllegalArgumentException("Expected numeric value for key '%s'".formatted(key));
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

  public record TimelineSample(double multiplier, double normalizationConstant) {}

  private record Seeds(String defaultSeed, Map<String, String> overrides) {

    Seeds(SeedsConfig config) {
      this(config.defaultSeed(), config.overrides());
    }

    long forKey(String explicitSeed, String type, String fallback) {
      String source = explicitSeed;
      if (source == null || source.isBlank()) {
        source = overrides.get(type);
      }
      if (source == null || source.isBlank()) {
        source = defaultSeed;
      }
      if (source == null || source.isBlank()) {
        source = fallback;
      }
      return toSeed(source);
    }
  }

  private interface StepEvaluator {
    double valueAt(double progress);
  }

  private static final class FlatEvaluator implements StepEvaluator {
    private final double factor;

    private FlatEvaluator(double factor) {
      this.factor = factor;
    }

    @Override
    public double valueAt(double progress) {
      return factor;
    }
  }

  private static final class RampEvaluator implements StepEvaluator {
    private final double from;
    private final double to;

    private RampEvaluator(double from, double to) {
      this.from = from;
      this.to = to;
    }

    @Override
    public double valueAt(double progress) {
      double clamped = Math.max(0d, Math.min(1d, progress));
      return from + (to - from) * clamped;
    }
  }

  private static final class SinusEvaluator implements StepEvaluator {
    private final double center;
    private final double amplitude;
    private final double cycles;
    private final double phase;

    private SinusEvaluator(double center, double amplitude, double cycles, double phase) {
      this.center = center;
      this.amplitude = amplitude;
      this.cycles = cycles;
      this.phase = phase;
    }

    @Override
    public double valueAt(double progress) {
      double angle = phase + 2 * Math.PI * cycles * progress;
      return center + amplitude * Math.sin(angle);
    }
  }

  private static final class DutyEvaluator implements StepEvaluator {
    private final double onMillis;
    private final double offMillis;
    private final double high;
    private final double low;
    private final double stepDurationMillis;

    private DutyEvaluator(double onMillis, double offMillis, double high, double low, double stepDurationMillis) {
      this.onMillis = Math.max(onMillis, 0d);
      this.offMillis = Math.max(offMillis, 0d);
      this.high = high;
      this.low = low;
      this.stepDurationMillis = stepDurationMillis;
    }

    @Override
    public double valueAt(double progress) {
      if (stepDurationMillis <= EPSILON) {
        return high;
      }
      double cycleMillis = Math.max(onMillis + offMillis, EPSILON);
      double elapsed = Math.max(0d, Math.min(1d, progress)) * stepDurationMillis;
      double position = elapsed % cycleMillis;
      return position < onMillis ? high : low;
    }
  }

  private interface StepMutator {
    double apply(double value, double offsetMillis);
  }

  private static final class CapMutator implements StepMutator {
    private final Double min;
    private final Double max;

    private CapMutator(Map<String, Object> settings) {
      this.min = settings.containsKey("min") ? readDouble(settings, "min", 0d) : null;
      this.max = settings.containsKey("max") ? readDouble(settings, "max", Double.POSITIVE_INFINITY) : null;
    }

    @Override
    public double apply(double value, double offsetMillis) {
      double result = value;
      if (min != null) {
        result = Math.max(min, result);
      }
      if (max != null && !Double.isInfinite(max)) {
        result = Math.min(max, result);
      }
      return result;
    }
  }

  private static final class NoiseMutator implements StepMutator {
    private final double[] factors;
    private final double bucketMillis;

    private NoiseMutator(Map<String, Object> settings, long seed, double stepDurationMillis) {
      double pct = readDouble(settings, "pct", 0d);
      this.bucketMillis = Math.max(readDouble(settings, "bucketMs", 1000d), 1d);
      int buckets = Math.max(1, (int) Math.ceil(stepDurationMillis / bucketMillis));
      this.factors = new double[buckets];
      SplittableRandom random = new SplittableRandom(seed);
      double amplitude = pct / 100d;
      for (int i = 0; i < buckets; i++) {
        double draw = random.nextDouble(-1d, 1d);
        factors[i] = 1d + draw * amplitude;
      }
    }

    @Override
    public double apply(double value, double offsetMillis) {
      int index = (int) Math.min(factors.length - 1, Math.max(0, Math.floor(offsetMillis / bucketMillis)));
      return value * factors[index];
    }
  }

  private static final class BurstMutator implements StepMutator {
    private final List<Interval> bursts;
    private final double liftMultiplier;

    private BurstMutator(Map<String, Object> settings, long seed, double stepDurationMillis) {
      double liftPct = readDouble(settings, "liftPct", 0d);
      this.liftMultiplier = 1d + liftPct / 100d;
      Range duration = range(settings, "durationMs", stepDurationMillis);
      Range every = range(settings, "everyMs", stepDurationMillis);
      double jitterStart = readDouble(settings, "jitterStartMs", 0d);
      SplittableRandom random = new SplittableRandom(seed);
      List<Interval> intervals = new ArrayList<>();
      double cursor = jitterStart > 0d ? random.nextDouble(0d, jitterStart) : 0d;
      while (cursor < stepDurationMillis) {
        double burstLength = duration.draw(random);
        double end = Math.min(cursor + burstLength, stepDurationMillis);
        intervals.add(new Interval(cursor, end));
        double gap = every.draw(random);
        if (gap <= 0d) {
          break;
        }
        cursor = end + gap;
      }
      this.bursts = List.copyOf(intervals);
    }

    @Override
    public double apply(double value, double offsetMillis) {
      for (Interval interval : bursts) {
        if (interval.contains(offsetMillis)) {
          return value * liftMultiplier;
        }
      }
      return value;
    }
  }

  private interface GlobalMutator {
    double apply(double value, double patternMillis, double durationMillis);
  }

  private static final class SpikeGlobalMutator implements GlobalMutator {
    private final double centerMillis;
    private final double halfWidthMillis;
    private final double liftMultiplier;

    private SpikeGlobalMutator(Map<String, Object> settings, Duration patternDuration) {
      Object at = settings.get("at");
      if (at == null) {
        throw new IllegalArgumentException("spike mutator requires 'at'");
      }
      this.centerMillis = parseClockOffset(at.toString());
      Object widthValue = settings.getOrDefault("width", "0");
      Duration width = parseDuration(widthValue);
      this.halfWidthMillis = width.toNanos() * MILLIS_PER_NANO / 2d;
      this.liftMultiplier = 1d + readDouble(settings, "liftPct", 0d) / 100d;
    }

    @Override
    public double apply(double value, double patternMillis, double durationMillis) {
      double distance = wrapDistance(patternMillis, centerMillis, durationMillis);
      if (distance <= halfWidthMillis) {
        return value * liftMultiplier;
      }
      return value;
    }
  }

  private static class StepSegment {
    private final String id;
    private final double startMillis;
    private final double endMillis;
    private final double stepDurationMillis;
    private final StepEvaluator evaluator;
    private final List<StepMutator> mutators;
    private final Transition transition;
    private StepSegment next;

    private StepSegment(String id,
                        double startMillis,
                        double endMillis,
                        StepEvaluator evaluator,
                        List<StepMutator> mutators,
                        Transition transition) {
      this.id = id;
      this.startMillis = startMillis;
      this.endMillis = endMillis;
      this.stepDurationMillis = Math.max(endMillis - startMillis, EPSILON);
      this.evaluator = evaluator;
      this.mutators = mutators;
      this.transition = transition;
    }

    private void setNext(StepSegment next) {
      this.next = next;
    }

    private boolean contains(double patternMillis, boolean last) {
      boolean within = patternMillis >= startMillis && patternMillis < endMillis;
      return within || (last && Math.abs(patternMillis - endMillis) < EPSILON);
    }

    private double valueAt(double patternMillis) {
      double offset = Math.max(0d, Math.min(patternMillis - startMillis, stepDurationMillis));
      double progress = offset / stepDurationMillis;
      double value = evaluator.valueAt(progress);
      for (StepMutator mutator : mutators) {
        value = mutator.apply(value, offset);
      }
      if (transition.durationMillis > EPSILON && patternMillis >= transition.startMillis) {
        double u = (patternMillis - transition.startMillis) / transition.durationMillis;
        double alpha = transition.blend(u);
        double nextValue = next.initialValue();
        value = value * (1d - alpha) + nextValue * alpha;
      }
      return value;
    }

    private double initialValue() {
      double value = evaluator.valueAt(0d);
      for (StepMutator mutator : mutators) {
        value = mutator.apply(value, 0d);
      }
      return value;
    }
  }

  private record Transition(TransitionType type, double startMillis, double durationMillis) {

    private static Transition none(double endMillis) {
      return new Transition(TransitionType.NONE, endMillis, 0d);
    }

    private double blend(double progress) {
      double clamped = Math.max(0d, Math.min(1d, progress));
      return switch (type) {
        case NONE -> 0d;
        case LINEAR -> clamped;
        case SMOOTH -> clamped * clamped * (3d - 2d * clamped);
      };
    }
  }

  private record Interval(double start, double end) {
    boolean contains(double value) {
      return value >= start && value <= end;
    }
  }

  private record Range(double min, double max) {
    double draw(SplittableRandom random) {
      double boundedMin = Math.min(min, max);
      double boundedMax = Math.max(min, max);
      if (Math.abs(boundedMax - boundedMin) < EPSILON) {
        return boundedMax;
      }
      double sample = random.nextDouble();
      return boundedMin + (boundedMax - boundedMin) * sample;
    }
  }

  private static Range range(Map<String, Object> settings, String key, double defaultValue) {
    Object raw = settings.get(key);
    if (raw instanceof Map<?, ?> map) {
      double min = readDouble(castMap(map), "min", defaultValue);
      double max = readDouble(castMap(map), "max", defaultValue);
      return new Range(min, max);
    }
    if (raw instanceof Number number) {
      double value = number.doubleValue();
      return new Range(value, value);
    }
    if (raw instanceof String text && !text.isBlank()) {
      double value = Double.parseDouble(text);
      return new Range(value, value);
    }
    return new Range(defaultValue, defaultValue);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castMap(Map<?, ?> source) {
    return (Map<String, Object>) source;
  }

  private static double parseClockOffset(String value) {
    if (value.startsWith("24:")) {
      return Duration.ofHours(24).toNanos() * MILLIS_PER_NANO;
    }
    LocalTime time = LocalTime.parse(value);
    return Duration.between(LocalTime.MIN, time).toNanos() * MILLIS_PER_NANO;
  }

  private static Duration parseDuration(Object value) {
    if (value instanceof Duration duration) {
      return duration;
    }
    return DurationStyle.detectAndParse(value.toString());
  }

  private static double wrapDistance(double current, double center, double durationMillis) {
    if (durationMillis <= 0d) {
      return Math.abs(current - center);
    }
    double diff = Math.abs(current - center) % durationMillis;
    double alt = durationMillis - diff;
    if (alt >= durationMillis) {
      alt -= durationMillis;
    }
    return Math.min(diff, alt);
  }
}
