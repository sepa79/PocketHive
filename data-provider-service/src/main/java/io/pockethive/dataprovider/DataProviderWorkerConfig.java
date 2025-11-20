package io.pockethive.dataprovider;

import java.util.Map;

public record DataProviderWorkerConfig(Map<String, String> headers) {

  public DataProviderWorkerConfig {
    headers = headers == null ? Map.of() : Map.copyOf(headers);
  }
}
