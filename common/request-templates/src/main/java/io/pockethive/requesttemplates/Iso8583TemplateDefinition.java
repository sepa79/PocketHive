package io.pockethive.requesttemplates;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Iso8583TemplateDefinition(
    String serviceId,
    String callId,
    String protocol, // ISO8583
    String wireProfileId,
    String payloadAdapter, // RAW_HEX, FIELD_LIST_XML
    String bodyTemplate,
    Map<String, String> headersTemplate,
    IsoSchemaRef schemaRef,
    Map<String, Object> auth
) implements TemplateDefinition {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record IsoSchemaRef(
      String schemaRegistryRoot,
      String schemaId,
      String schemaVersion,
      String schemaAdapter,
      String schemaFile
  ) {
  }
}
