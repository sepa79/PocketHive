package io.pockethive.swarm.model.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.pockethive.swarm.model.BeeRoles;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SwarmStateViewTest {

  private static final String SWARM_ID = "synthetic-detail-swarm";
  private static final String RUN_ID = "synthetic-detail-run";
  private static final String TEMPLATE_ID = "synthetic-detail-scenario";
  private static final String CONTROLLER_IMAGE = "ghcr.io/pockethive/swarm-controller:synthetic";
  private static final String WORKER_INSTANCE = "synthetic-detail-swarm-generator-1";
  private static final String WORKER_IMAGE = "ghcr.io/pockethive/generator:synthetic";
  private static final Instant OBSERVED_AT = Instant.parse("2026-07-22T12:34:56Z");
  private static final ObjectMapper MAPPER = JsonMapper.builder()
      .addModule(new JavaTimeModule())
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
      .serializationInclusion(JsonInclude.Include.ALWAYS)
      .build();

  @Test
  void absentRuntimeIntentRequiresStoppedWorkloadIntent() {
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> new SwarmStateView(
        "alpha",
        "run-1",
        RuntimeIntent.ABSENT,
        WorkloadIntent.RUNNING,
        ControllerState.UNKNOWN,
        WorkloadState.UNKNOWN,
        Health.UNKNOWN,
        RuntimeResourceState.UNKNOWN,
        null,
        true,
        null,
        null,
        null,
        List.of(),
        Map.of()));
    assertTrue(error.getMessage().contains("ABSENT"));
    assertTrue(error.getMessage().contains("STOPPED"));
  }

  @Test
  void rejectsNullObservationInsteadOfSubstitutingAnEmptyObject() {
    NullPointerException error = assertThrows(NullPointerException.class, () -> initialState(null));

    assertEquals("observation", error.getMessage());
  }

  @Test
  void initialStateSerializesAsCompleteFixtureWithEmptyObservationAndExplicitNulls() throws IOException {
    JsonNode serialized = assertMatchesFixture("swarm-state-initial.json", initialState(Map.of()));

    assertTrue(serialized.required("observation").isObject());
    assertTrue(serialized.required("observation").isEmpty());
    assertTrue(serialized.required("observedAt").isNull());
    assertTrue(serialized.required("activeOperation").isNull());
    assertTrue(serialized.required("observationStale").booleanValue());
    assertTrue(serialized.required("bees").isEmpty());
  }

  @Test
  void observedStateSerializesAsCompleteFixtureWithWorkerContextAndExplicitNulls() throws IOException {
    Map<String, Object> worker = Map.of(
        "role", BeeRoles.GENERATOR,
        "instance", WORKER_INSTANCE,
        "enabled", false,
        "tps", 0,
        "lastSeenAt", OBSERVED_AT,
        "stale", false,
        "ioState", Map.of("work", Map.of("input", "ok", "output", "ok")),
        "runtime", new RuntimeMetadata(TEMPLATE_ID, RUN_ID, null, WORKER_IMAGE, null),
        "config", Map.of("message", Map.of("body", "Synthetic payload for " + WORKER_INSTANCE)));
    Map<String, Object> observation = Map.of(
        "controllerState", ControllerState.READY,
        "workloadState", WorkloadState.STOPPED,
        "health", Health.HEALTHY,
        "startupReady", true,
        "startupArtifactSha256", "a".repeat(64),
        "watermarkAt", OBSERVED_AT,
        "expectedWorkers", List.of(new Target(BeeRoles.GENERATOR, WORKER_INSTANCE)),
        "workers", List.of(worker));
    SwarmStateView state = new SwarmStateView(
        SWARM_ID,
        RUN_ID,
        RuntimeIntent.PRESENT,
        WorkloadIntent.STOPPED,
        ControllerState.READY,
        WorkloadState.STOPPED,
        Health.HEALTHY,
        RuntimeResourceState.PRESENT,
        OBSERVED_AT,
        false,
        null,
        TEMPLATE_ID,
        CONTROLLER_IMAGE,
        List.of(new WorkerSummary(BeeRoles.GENERATOR, WORKER_INSTANCE, WORKER_IMAGE)),
        observation);

    JsonNode serialized = assertMatchesFixture("swarm-state-observed.json", state);
    Set<ValidationMessage> contextErrors = controllerStatusFullContextSchema()
        .validate(serialized.required("observation"));
    assertTrue(contextErrors.isEmpty(), contextErrors::toString);

    assertEquals(OBSERVED_AT.toString(), serialized.required("observedAt").textValue());
    assertTrue(serialized.required("activeOperation").isNull());
    JsonNode runtime = serialized.required("observation").required("workers").get(0).required("runtime");
    assertTrue(runtime.required("containerId").isNull());
    assertTrue(runtime.required("stackName").isNull());
  }

  @Test
  void observationIsDefensivelyCopiedAndImmutable() {
    Map<String, Object> observation = new LinkedHashMap<>();
    observation.put("startupReady", true);
    SwarmStateView state = initialState(observation);

    observation.put("startupReady", false);

    assertEquals(true, state.observation().get("startupReady"));
    assertThrows(UnsupportedOperationException.class, () -> state.observation().put("startupReady", false));
  }

  @Test
  void canonicalSchemaRejectsNullObservation() throws IOException {
    ObjectNode serialized = MAPPER.valueToTree(initialState(Map.of()));
    serialized.putNull("observation");

    assertFalse(lifecycleSchema().validate(serialized).isEmpty());
  }

  @Test
  void canonicalSchemaRejectsOmittedObservation() throws IOException {
    ObjectNode serialized = MAPPER.valueToTree(initialState(Map.of()));
    serialized.remove("observation");

    assertFalse(lifecycleSchema().validate(serialized).isEmpty());
  }

  private static SwarmStateView initialState(Map<String, Object> observation) {
    return new SwarmStateView(
        SWARM_ID,
        RUN_ID,
        RuntimeIntent.PRESENT,
        WorkloadIntent.STOPPED,
        ControllerState.PROVISIONING,
        WorkloadState.UNAVAILABLE,
        Health.UNKNOWN,
        RuntimeResourceState.PRESENT,
        null,
        true,
        null,
        TEMPLATE_ID,
        CONTROLLER_IMAGE,
        List.of(),
        observation);
  }

  private static JsonNode assertMatchesFixture(String resourceName, SwarmStateView state) throws IOException {
    JsonNode serialized = MAPPER.readTree(MAPPER.writeValueAsBytes(state));
    try (InputStream fixture = SwarmStateViewTest.class.getResourceAsStream(resourceName)) {
      assertNotNull(fixture, "Missing producer fixture: " + resourceName);
      assertEquals(MAPPER.readTree(fixture), serialized);
    }
    Set<ValidationMessage> errors = lifecycleSchema().validate(serialized);
    assertTrue(errors.isEmpty(), errors::toString);
    return serialized;
  }

  private static JsonSchema lifecycleSchema() throws IOException {
    try (InputStream schema = SwarmStateViewTest.class.getResourceAsStream("schema/swarm-lifecycle.schema.json")) {
      assertNotNull(schema, "Missing packaged canonical lifecycle schema");
      return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(MAPPER.readTree(schema));
    }
  }

  private static JsonSchema controllerStatusFullContextSchema() throws IOException {
    Path schemaPath = Path.of("..", "..", "docs", "spec", "control-events.schema.json")
        .toAbsolutePath().normalize();
    Path lifecyclePath = schemaPath.resolveSibling("swarm-lifecycle.schema.json");
    JsonNode schemaDocument = MAPPER.readTree(schemaPath.toFile());
    JsonNode lifecycleDocument = MAPPER.readTree(lifecyclePath.toFile());
    String schemaId = schemaDocument.required("$id").textValue();
    ObjectNode contextSchema = MAPPER.createObjectNode();
    contextSchema.put("$schema", schemaDocument.required("$schema").textValue());
    contextSchema.put("$ref", schemaId + "#/$defs/ControllerStatusFullContext");
    JsonSchemaFactory factory = JsonSchemaFactory.builder(
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012))
        .objectMapper(MAPPER)
        .addUriMappings(Map.of(
            schemaId, schemaPath.toUri().toString(),
            lifecycleDocument.required("$id").textValue(), lifecyclePath.toUri().toString()))
        .build();
    return factory.getSchema(contextSchema);
  }
}
