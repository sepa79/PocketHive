package io.pockethive.processor;

public record ProcessorWorkerConfig(String baseUrl) {

  public ProcessorWorkerConfig {
    baseUrl = sanitise(baseUrl);
  }
  private static String sanitise(String candidate) {
    if (candidate == null) {
      return null;
    }
    String trimmed = candidate.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
