package io.pockethive.processor;

public record ProcessorWorkerConfig(String baseUrl) {

  public ProcessorWorkerConfig {
    if (baseUrl == null || baseUrl.trim().isEmpty()) {
      throw new IllegalArgumentException("processor baseUrl must be provided");
    }
    baseUrl = baseUrl.trim();
  }
}
