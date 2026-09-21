package io.pockethive.swarm.model.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.pockethive.swarm.model.NetworkMode;
import org.junit.jupiter.api.Test;

class SwarmCreateRequestJsonCodecTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

  @Test
  void acceptsEveryRequiredFieldIncludingExplicitNulls() throws Exception {
    SwarmCreateRequest request = SwarmCreateRequestJsonCodec.fromJson(MAPPER.readTree("""
        {
          "templateId": "template-1",
          "idempotencyKey": "create-1",
          "autoPullImages": false,
          "sutId": null,
          "variablesProfileId": null,
          "networkMode": "DIRECT",
          "networkProfileId": null
        }
        """), MAPPER);

    assertEquals(NetworkMode.DIRECT, request.networkMode());
    assertNull(request.sutId());
  }

  @Test
  void rejectsOmittedNullableFieldsInsteadOfTreatingThemAsNull() throws Exception {
    SwarmLifecycleContractException exception = assertThrows(
        SwarmLifecycleContractException.class,
        () -> SwarmCreateRequestJsonCodec.fromJson(MAPPER.readTree("""
        {
          "templateId": "template-1",
          "idempotencyKey": "create-1",
          "autoPullImages": false,
          "networkMode": "DIRECT"
        }
        """), MAPPER));

    assertTrue(exception.getMessage().startsWith("Swarm create request schema validation failed:"));
  }

  @Test
  void rejectsNullForTheRequiredAutoPullImagesFlag() throws Exception {
    assertSchemaRejected("""
        {
          "templateId": "template-1",
          "idempotencyKey": "create-1",
          "autoPullImages": null,
          "sutId": null,
          "variablesProfileId": null,
          "networkMode": "DIRECT",
          "networkProfileId": null
        }
        """);
  }

  @Test
  void rejectsRetiredNotesAfterEveryCanonicalFieldPassesValidation() throws Exception {
    SwarmLifecycleContractException exception = assertThrows(
        SwarmLifecycleContractException.class,
        () -> SwarmCreateRequestJsonCodec.fromJson(MAPPER.readTree("""
        {
          "templateId": "template-1",
          "idempotencyKey": "create-1",
          "autoPullImages": false,
          "sutId": null,
          "variablesProfileId": null,
          "networkMode": "DIRECT",
          "networkProfileId": null,
          "notes": "retired"
        }
        """), MAPPER));

    assertTrue(exception.getMessage().startsWith("Swarm create request schema validation failed:"));
    assertTrue(exception.getMessage().contains("notes"));
  }

  @Test
  void schemaOwnsDirectAndProxiedModeRequirements() throws Exception {
    assertSchemaRejected("""
        {
          "templateId": "template-1",
          "idempotencyKey": "create-1",
          "autoPullImages": false,
          "sutId": null,
          "variablesProfileId": null,
          "networkMode": "DIRECT",
          "networkProfileId": "profile-1"
        }
        """);
    assertSchemaRejected("""
        {
          "templateId": "template-1",
          "idempotencyKey": "create-1",
          "autoPullImages": false,
          "sutId": null,
          "variablesProfileId": null,
          "networkMode": "PROXIED",
          "networkProfileId": "profile-1"
        }
        """);
    assertSchemaRejected("""
        {
          "templateId": "template-1",
          "idempotencyKey": "create-1",
          "autoPullImages": false,
          "sutId": "sut-1",
          "variablesProfileId": null,
          "networkMode": "PROXIED",
          "networkProfileId": null
        }
        """);
  }

  @Test
  void rejectsNonCanonicalWhitespaceInsteadOfNormalizingIt() throws Exception {
    assertSchemaRejected("""
        {
          "templateId": " template-1 ",
          "idempotencyKey": "create-1",
          "autoPullImages": false,
          "sutId": null,
          "variablesProfileId": null,
          "networkMode": "DIRECT",
          "networkProfileId": null
        }
        """);
  }

  @Test
  void typedFactoryUsesTheSameSchemaBoundaryAndSerializesRequiredNulls() throws Exception {
    SwarmCreateRequest request = SwarmCreateRequest.of(
        "template-1", "create-1", false, null, null, NetworkMode.DIRECT, null);

    ObjectMapper nonNullMapper = MAPPER.copy()
        .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    var serialized = nonNullMapper.readTree(nonNullMapper.writeValueAsBytes(request));
    assertTrue(serialized.has("sutId"));
    assertTrue(serialized.get("sutId").isNull());
    assertTrue(serialized.has("variablesProfileId"));
    assertTrue(serialized.get("variablesProfileId").isNull());
    assertTrue(serialized.has("networkProfileId"));
    assertTrue(serialized.get("networkProfileId").isNull());
  }

  private static void assertSchemaRejected(String json) throws Exception {
    SwarmLifecycleContractException exception = assertThrows(
        SwarmLifecycleContractException.class,
        () -> SwarmCreateRequestJsonCodec.fromJson(MAPPER.readTree(json), MAPPER));
    assertTrue(exception.getMessage().startsWith("Swarm create request schema validation failed:"));
  }
}
