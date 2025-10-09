package io.pockethive.processor;

public record ProcessorWorkerConfig(boolean enabled, String baseUrl) {

  public ProcessorWorkerConfig {
    baseUrl = normalize(baseUrl);
  }

  private static String normalize(String baseUrl) {
    if (baseUrl == null) {
      return "";
    }
    return baseUrl.trim();
  }
}
