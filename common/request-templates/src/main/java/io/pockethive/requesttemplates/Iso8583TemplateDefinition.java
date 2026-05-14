package io.pockethive.requesttemplates;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.pockethive.worker.sdk.auth.AuthRef;
import io.pockethive.swarm.model.ResultRules;
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
    AuthRef authRef,
    ResultRules resultRules
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
