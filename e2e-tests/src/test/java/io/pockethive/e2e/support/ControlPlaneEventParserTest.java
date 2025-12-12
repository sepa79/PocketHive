package io.pockethive.e2e.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.pockethive.control.AlertMessage;
import io.pockethive.control.CommandOutcome;
import io.pockethive.control.ControlScope;

class ControlPlaneEventParserTest {

  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  @Test
  void parsesCommandOutcomes() throws Exception {
    CommandOutcome outcome = new CommandOutcome(
        Instant.parse("2024-07-01T12:00:00Z"),
        "1",
        "outcome",
        "swarm-start",
        "swarm-controller:alpha",
        ControlScope.forInstance("swarm-test", "swarm-controller", "alpha"),
        "corr-1",
        "idem-1",
        Map.of("status", "Running")
    );
    byte[] body = mapper.writeValueAsBytes(outcome);

    ControlPlaneEventParser parser = new ControlPlaneEventParser(mapper);
    ControlPlaneEventParser.ParsedEvent parsed = parser.parse(
        "event.outcome.swarm-start.swarm-test.swarm-controller.alpha",
        body
    );

    assertNull(parsed.alert());
    assertNull(parsed.status());
    CommandOutcome parsedOutcome = parsed.outcome();
    assertNotNull(parsedOutcome);
    assertEquals("corr-1", parsedOutcome.correlationId());
    assertEquals("swarm-start", parsedOutcome.type());
    assertEquals("Running", parsedOutcome.data().get("status"));
  }

  @Test
  void parsesAlertMessages() throws Exception {
    AlertMessage alert = new AlertMessage(
        Instant.parse("2024-07-01T12:00:01Z"),
        "1",
        "event",
        "alert",
        "swarm-controller:alpha",
        ControlScope.forInstance("swarm-test", "swarm-controller", "alpha"),
        "corr-2",
        "idem-2",
        new AlertMessage.AlertData(
            "error",
            "io.out-of-data",
            "Out of data",
            "RedisOutOfData",
            "dataset=orders",
            "logs://example",
            Map.of("dataset", "orders")
        )
    );
    byte[] body = mapper.writeValueAsBytes(alert);

    ControlPlaneEventParser parser = new ControlPlaneEventParser(mapper);
    ControlPlaneEventParser.ParsedEvent parsed = parser.parse(
        "event.alert.alert.swarm-test.swarm-controller.alpha",
        body
    );

    assertNull(parsed.outcome());
    assertNull(parsed.status());
    AlertMessage parsedAlert = parsed.alert();
    assertNotNull(parsedAlert);
    assertEquals("corr-2", parsedAlert.correlationId());
    assertEquals("alert", parsedAlert.type());
    assertEquals("io.out-of-data", parsedAlert.data().code());
  }

  @Test
  void parsesStatusEventsFromFixture() throws Exception {
    ControlPlaneEventParser parser = new ControlPlaneEventParser(mapper);
    try (InputStream stream = getClass().getResourceAsStream("/fixtures/status-full.json")) {
      assertNotNull(stream, "Fixture /fixtures/status-full.json is missing");
      byte[] body = stream.readAllBytes();
      ControlPlaneEventParser.ParsedEvent parsed = parser.parse(
          "event.metric.status-full.swarm-alpha.processor.processor-1",
          body
      );

      StatusEvent status = assertInstanceOf(StatusEvent.class, parsed.status());
      assertNull(parsed.outcome());
      assertNull(parsed.alert());
      assertEquals("metric", status.kind());
      assertEquals("status-full", status.type());
      assertEquals("processor", status.role());
      assertEquals("processor-1", status.instance());
      assertEquals("swarm-alpha", status.swarmId());
      assertEquals(Instant.parse("2024-07-01T12:34:56Z"), status.timestamp());
      assertEquals(Instant.parse("2024-07-01T12:34:50Z").toString(), status.data().context().get("watermark"));
      assertEquals(123L, status.data().tps());
      assertEquals(List.of("processor.input"), status.data().io().work().queues().in());
    }
  }
}
