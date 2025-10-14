package io.pockethive.examples.starter.generator;

/**
 * Typed configuration for the sample generator worker.
 */
public record SampleGeneratorConfig(
    boolean enabled,
    double ratePerSecond,
    String message
) {

  public SampleGeneratorConfig {
    ratePerSecond = Math.max(0.0, ratePerSecond);
    message = message == null ? "" : message;
  }
}
