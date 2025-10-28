package io.pockethive.moderator;

import io.pockethive.moderator.shaper.config.GlobalMutatorConfig;
import io.pockethive.moderator.shaper.config.JitterConfig;
import io.pockethive.moderator.shaper.config.NormalizationConfig;
import io.pockethive.moderator.shaper.config.PatternConfig;
import io.pockethive.moderator.shaper.config.PatternConfigValidator;
import io.pockethive.moderator.shaper.config.RunConfig;
import io.pockethive.moderator.shaper.config.SeedsConfig;
import io.pockethive.moderator.shaper.config.TimeConfig;
import java.util.List;
import java.util.Objects;

public record ModeratorWorkerConfig(boolean enabled,
                                    TimeConfig time,
                                    RunConfig run,
                                    PatternConfig pattern,
                                    NormalizationConfig normalization,
                                    List<GlobalMutatorConfig> globalMutators,
                                    JitterConfig jitter,
                                    SeedsConfig seeds) {

  public ModeratorWorkerConfig {
    time = time == null ? new TimeConfig(null, null, null) : time;
    run = run == null ? new RunConfig(null) : run;
    pattern = Objects.requireNonNull(pattern, "pattern");
    normalization = normalization == null ? NormalizationConfig.disabled() : normalization;
    globalMutators = globalMutators == null || globalMutators.isEmpty() ? List.of() : List.copyOf(globalMutators);
    jitter = jitter == null ? JitterConfig.disabled() : jitter;
    seeds = seeds == null ? SeedsConfig.empty() : seeds;

    PatternConfigValidator.ensureValid(pattern, normalization);
  }
}
