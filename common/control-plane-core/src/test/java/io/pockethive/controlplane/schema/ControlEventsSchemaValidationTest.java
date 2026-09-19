package io.pockethive.controlplane.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class ControlEventsSchemaValidationTest {

  private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

  @Test
  void fixturesValidateAgainstSchema() throws Exception {
    ControlEventsSchemaValidator.assertValid(normalizeEnvelope(resourceNode("/io/pockethive/controlplane/payload/status-snapshot.json")));
    ControlEventsSchemaValidator.assertValid(normalizeEnvelope(resourceNode("/io/pockethive/controlplane/payload/status-delta.json")));
    ControlEventsSchemaValidator.assertValid(normalizeEnvelope(payloadFromWrapper("/io/pockethive/controlplane/messaging/status-delta-event.json")));
    ControlEventsSchemaValidator.assertValid(normalizeEnvelope(payloadFromWrapper("/io/pockethive/controlplane/messaging/result-event.json")));
    ControlEventsSchemaValidator.assertValid(normalizeEnvelope(payloadFromWrapper("/io/pockethive/controlplane/messaging/error-event.json")));
    ControlEventsSchemaValidator.assertValid(controllerStatusFullWithWorkerRuntime());
  }

  private static JsonNode controllerStatusFullWithWorkerRuntime() throws Exception {
    return MAPPER.readTree("""
        {
          "timestamp": "2024-01-01T00:00:00Z",
          "version": "2",
          "kind": "metric",
          "type": "status-full",
          "origin": "controller-1",
          "scope": {"swarmId": "swarm-1", "role": "swarm-controller", "instance": "controller-1"},
          "correlationId": null,
          "idempotencyKey": null,
          "runtime": {"templateId": "template-1", "runId": "run-1"},
          "data": {
            "config": {},
            "startedAt": "2024-01-01T00:00:00Z",
            "io": {},
            "ioState": {},
            "context": {
              "controllerState": "READY",
              "workloadState": "RUNNING",
              "health": "HEALTHY",
              "startupReady": true,
              "startupArtifactSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "watermarkAt": "2024-01-01T00:00:00Z",
              "expectedWorkers": [],
              "workers": [{
                "role": "generator",
                "instance": "generator-1",
                "enabled": true,
                "lastSeenAt": "2024-01-01T00:00:00Z",
                "stale": false,
                "tps": 0,
                "runtime": {"templateId": "template-1", "runId": "run-1"},
                "ioState": {}
              }]
            }
          }
        }
        """);
  }

  private static JsonNode resourceNode(String name) throws Exception {
    return MAPPER.readTree(resourceText(name));
  }

  private static JsonNode payloadFromWrapper(String name) throws Exception {
    JsonNode wrapper = resourceNode(name);
    JsonNode payload = wrapper.path("payload");
    if (!payload.isObject()) {
      throw new IllegalStateException("Fixture wrapper must contain object payload: " + name);
    }
    return payload.deepCopy();
  }

  private static JsonNode normalizeEnvelope(JsonNode node) {
    if (node instanceof ObjectNode obj) {
      JsonNode ts = obj.get("timestamp");
      if (ts != null && ts.isTextual() && "<<ANY>>".equals(ts.asText())) {
        obj.put("timestamp", "2024-01-01T00:00:00Z");
      }
      return obj;
    }
    return node;
  }

  private static String resourceText(String name) throws Exception {
    try (var in = ControlEventsSchemaValidationTest.class.getResourceAsStream(name)) {
      if (in == null) {
        throw new IllegalStateException("Missing test resource: " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
