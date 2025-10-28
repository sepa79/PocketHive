package io.pockethive.moderator.shaper.config;

import java.util.List;
import java.util.Map;

public record StepConfig(String id,
                         StepRangeConfig range,
                         StepMode mode,
                         Map<String, Object> params,
                         List<StepMutatorConfig> mutators,
                         TransitionConfig transition) {

  public StepConfig {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("step id must be provided");
    }
    if (range == null) {
      throw new IllegalArgumentException("step range must be provided");
    }
    mode = mode == null ? StepMode.FLAT : mode;
    params = params == null || params.isEmpty() ? Map.of() : Map.copyOf(params);
    mutators = mutators == null || mutators.isEmpty() ? List.of() : List.copyOf(mutators);
    transition = transition == null ? TransitionConfig.none() : transition;
  }
}
