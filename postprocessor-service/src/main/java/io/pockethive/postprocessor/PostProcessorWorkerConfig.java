package io.pockethive.postprocessor;

public record PostProcessorWorkerConfig(boolean enabled, boolean publishAllMetrics) {

  public PostProcessorWorkerConfig(boolean enabled) {
    this(enabled, false);
  }
}
