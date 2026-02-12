package io.pockethive.httpbuilder;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * On-disk representation of an HTTP call template.
 *
 * This is intentionally minimal: enough to build an HTTP envelope for the processor worker.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HttpTemplateDefinition(
    String serviceId,
    String callId,
    String method,
    String pathTemplate,
    String bodyTemplate,
    Map<String, String> headersTemplate,
    /**
     * Authoring-only metadata used by the Hive UI to locate a JSON Schema (within the scenario bundle)
     * for rendering a richer editor. Runtime workers must ignore it.
     */
    String schemaRef,
    ResultRules resultRules
) {
  public HttpTemplateDefinition {
    resultRules = resultRules == null ? ResultRules.empty() : resultRules;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ResultRules(
      ValueExtractor businessCode,
      String successRegex,
      List<DimensionExtractor> dimensions
  ) {
    public ResultRules {
      dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
    }

    public static ResultRules empty() {
      return new ResultRules(null, null, List.of());
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ValueExtractor(
      Source source,
      String pattern,
      String header
  ) {
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
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
