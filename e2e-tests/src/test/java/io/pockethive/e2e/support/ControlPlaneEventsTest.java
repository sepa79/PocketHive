package io.pockethive.e2e.support;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class ControlPlaneEventsTest {

  private ControlPlaneEventParser parser;
  private ControlPlaneEvents events;

  @BeforeEach
  void setUp() {
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    parser = new ControlPlaneEventParser(mapper);
    events = new ControlPlaneEvents(parser);
  }

  @Test
  void tracksLatestStatusesByIdentity() throws Exception {
    StatusEvent status = statusFixture();
    events.recordStatus("event.metric.status-full.swarm-alpha.processor.processor-1", status, Instant.parse("2024-07-01T12:35:00Z"));
    events.recordStatus("event.metric.status-delta.swarm-alpha.processor.processor-1", status, Instant.parse("2024-07-01T12:35:05Z"));

    assertEquals(2, events.statusesForSwarm("swarm-alpha").size());
    StatusEvent latest = events.latestStatusEvent("swarm-alpha", "processor", "processor-1").orElseThrow();
    assertEquals("processor-1", latest.instance());
    assertTrue(events.lastStatusSeenAt("swarm-alpha", "processor", "processor-1").isPresent());
    assertEquals(Instant.parse("2024-07-01T12:35:05Z"),
        events.lastStatusSeenAt("swarm-alpha", "processor", "processor-1").orElseThrow());
  }

  @Test
  void exposesLatestStatusDeltaEvent() throws Exception {
    StatusEvent status = statusFixture();
    StatusEvent delta = toDelta(status);
    events.recordStatus("event.metric.status-full.swarm-alpha.processor.processor-1", status, Instant.parse("2024-07-01T12:34:00Z"));
    events.recordStatus("event.metric.status-delta.swarm-alpha.processor.processor-1", delta, Instant.parse("2024-07-01T12:34:10Z"));

    StatusEvent latestDelta = events.latestStatusDeltaEvent("swarm-alpha", "processor", "processor-1").orElseThrow();
    assertEquals("status-delta", latestDelta.type());
    assertEquals("processor-1", latestDelta.instance());
  }

  @Test
  void assertsQueueLayouts() throws Exception {
    StatusEvent status = statusFixture();
    events.recordStatus("event.metric.status-full.swarm-alpha.processor.processor-1", status, Instant.parse("2024-07-01T12:34:56Z"));

    assertDoesNotThrow(() -> events.assertWorkQueues(
        "swarm-alpha",
        "processor",
        "processor-1",
        List.of("processor.input"),
        List.of("rk.alpha"),
        List.of("rk.beta")
    ));

    assertDoesNotThrow(() -> events.assertControlQueues(
        "swarm-alpha",
        "processor",
        "processor-1",
        List.of("control.input"),
        List.of("event.metric.status-full.swarm-alpha"),
        List.of("event.metric.status-delta.swarm-alpha")
    ));

    AssertionError mismatch = assertThrows(AssertionError.class, () -> events.assertWorkQueues(
        "swarm-alpha",
        "processor",
        "processor-1",
        List.of("processor.input"),
        List.of("rk.beta"),
        List.of("rk.beta")
    ));
    assertTrue(mismatch.getMessage().contains("work.routes"));
  }

  private StatusEvent statusFixture() throws IOException {
    try (InputStream stream = getClass().getResourceAsStream("/fixtures/status-full.json")) {
      assertNotNull(stream, "Fixture /fixtures/status-full.json is missing");
      byte[] body = stream.readAllBytes();
      ControlPlaneEventParser.ParsedEvent parsed = parser.parse(
          "event.metric.status-full.swarm-alpha.processor.processor-1",
          body
      );
      StatusEvent status = parsed.status();
      assertNotNull(status, "Expected parsed status event");
      return status;
    }
  }

  private StatusEvent toDelta(StatusEvent source) {
    return new StatusEvent(
        source.timestamp(),
        source.version(),
        source.kind(),
        "status-delta",
        source.origin(),
        source.scope(),
        source.correlationId(),
        source.idempotencyKey(),
        source.data()
    );
  }
}
