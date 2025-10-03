package io.pockethive.worker.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkerMessageEnvelopeCodecTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private WorkerMessageEnvelopeCodec codec;

  @BeforeEach
  void setUp() {
    codec = new WorkerMessageEnvelopeCodec(MAPPER);
  }

  @Test
  void decodeRoundTripPreservesPayload() {
    Map<String, Object> document = new LinkedHashMap<>();
    document.put("messageId", "msg-123");
    document.put("timestamp", "2024-01-01T12:34:56Z");
    document.put("event", "status");
    document.put("kind", "status-full");
    document.put("version", "1.0");
    document.put("role", "generator");
    document.put("instance", "gen-1");
    document.put("swarmId", "swarm-A");
    document.put("location", "lab");
    document.put("origin", "gen-1");
    document.put("enabled", Boolean.TRUE);
    document.put("queues", Map.of(
        "control", Map.of("in", List.of("ph.control.swarm-A.generator.gen-1")),
        "work", Map.of("out", List.of("ph.work.swarm-A.generator"))));
    document.put("data", Map.of("tps", 42));

    WorkerMessageEnvelope envelope = codec.decode(document);

    assertThat(envelope.messageId()).isEqualTo("msg-123");
    assertThat(envelope.timestamp()).isEqualTo(Instant.parse("2024-01-01T12:34:56Z"));
    assertThat(envelope.event()).isEqualTo("status");
    assertThat(envelope.kind()).isEqualTo("status-full");
    assertThat(envelope.version()).isEqualTo("1.0");
    assertThat(envelope.role()).isEqualTo("generator");
    assertThat(envelope.instance()).isEqualTo("gen-1");
    assertThat(envelope.swarmId()).isEqualTo("swarm-A");
    assertThat(envelope.location()).isEqualTo("lab");
    assertThat(envelope.origin()).isEqualTo("gen-1");
    assertThat(envelope.payload().path("enabled").asBoolean()).isTrue();
    assertThat(envelope.payload().path("queues").path("control").path("in").get(0).asText())
        .isEqualTo("ph.control.swarm-A.generator.gen-1");
    assertThat(envelope.payload().path("data").path("tps").asInt()).isEqualTo(42);

    Map<String, Object> reencoded = codec.encodeToMap(envelope);
    assertThat(reencoded).isEqualTo(document);
  }

  @Test
  void encodeProducesTopLevelFields() {
    ObjectNode payload = MAPPER.createObjectNode();
    payload.put("enabled", false);
    payload.putObject("data").put("tps", 7);

    WorkerMessageEnvelope envelope = new WorkerMessageEnvelope(
        "msg-456",
        Instant.parse("2024-02-02T10:00:00Z"),
        "status",
        "status-delta",
        null,
        "processor",
        "proc-1",
        null,
        null,
        null,
        null,
        null,
        payload);

    Map<String, Object> encoded = codec.encodeToMap(envelope);

    assertThat(encoded).containsEntry("messageId", "msg-456");
    assertThat(encoded).containsEntry("timestamp", "2024-02-02T10:00:00Z");
    assertThat(encoded).containsEntry("event", "status");
    assertThat(encoded).containsEntry("kind", "status-delta");
    assertThat(encoded).containsEntry("role", "processor");
    assertThat(encoded).containsEntry("instance", "proc-1");
    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) encoded.get("data");
    assertThat(data).containsEntry("tps", 7);
    assertThat(encoded).containsEntry("enabled", false);
  }

  @Test
  void decodeRejectsBlankMessageId() {
    Map<String, Object> document = Map.of(
        "messageId", "  ",
        "timestamp", "2024-01-01T00:00:00Z");

    assertThatThrownBy(() -> codec.decode(document))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("messageId");
  }

  @Test
  void decodeRejectsBlankTimestamp() {
    Map<String, Object> document = Map.of(
        "messageId", "msg-1",
        "timestamp", "");

    assertThatThrownBy(() -> codec.decode(document))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("timestamp");
  }

  @Test
  void decodeRejectsInvalidTimestamp() {
    Map<String, Object> document = Map.of(
        "messageId", "msg-1",
        "timestamp", "not-a-timestamp");

    assertThatThrownBy(() -> codec.decode(document))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("timestamp")
        .hasCauseInstanceOf(java.time.format.DateTimeParseException.class);
  }
}
