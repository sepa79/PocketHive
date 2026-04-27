package io.pockethive.swarm.model;

import java.util.List;

public record ResultRules(
    ValueExtractor businessCode,
    String successRegex,
    List<DimensionExtractor> dimensions
) {
  public ResultRules {
    dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
  }

  public record ValueExtractor(
      Source source,
      String pattern,
      String header
  ) {
  }

  public record DimensionExtractor(
      String name,
      Source source,
      String pattern,
      String header
  ) {
  }

  public enum Source {
    REQUEST_BODY,
    RESPONSE_BODY,
    REQUEST_HEADER,
    RESPONSE_HEADER
  }
}
