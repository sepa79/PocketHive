package io.pockethive.e2e.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.pockethive.control.CommandOutcome;
import io.pockethive.control.AlertMessage;
import io.pockethive.controlplane.messaging.Alerts;
import io.pockethive.swarm.model.lifecycle.TerminalStatus;

class ControlPlaneEventParserTest {

  @Test
  void statusProjectionHasOnlyTheCanonicalEnvelopeAsState() {
    assertEquals(1, StatusEvent.class.getRecordComponents().length);
    assertEquals(io.pockethive.control.StatusMetric.class,
        StatusEvent.class.getRecordComponents()[0].getType());
  }

  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  @Test
  void parsesCommandOutcomes() {
    byte[] body = """
        {
          "timestamp":"2024-07-01T12:00:00Z","version":"2","kind":"outcome",
          "type":"swarm-start","origin":"orchestrator:local",
          "scope":{"swarmId":"swarm-test","role":"orchestrator","instance":"local"},
          "correlationId":"corr-1","idempotencyKey":"idem-1",
          "runtime":{"templateId":"tpl-1","runId":"run-1"},
          "data":{"status":"Succeeded","retryable":false,"context":{
            "target":{"role":"swarm-controller","instance":"controller-1"},
            "requestedWorkloadState":"RUNNING","observedWorkloadState":"RUNNING",
            "nonConvergedWorkers":[]
          }}
        }
        """.getBytes(UTF_8);

    ControlPlaneEventParser parser = new ControlPlaneEventParser();
    ControlPlaneEventParser.ParsedEvent parsed = parser.parse(
        "event.outcome.swarm-start.swarm-test.orchestrator.local",
        body
    );

    assertNull(parsed.alert());
    assertNull(parsed.status());
    CommandOutcome parsedOutcome = parsed.outcome();
    assertNotNull(parsedOutcome);
    assertEquals("corr-1", parsedOutcome.correlationId());
    assertEquals("swarm-start", parsedOutcome.type());
    assertEquals(TerminalStatus.SUCCEEDED, parsedOutcome.data().status());
    assertEquals("RUNNING", parsedOutcome.data().context().get("observedWorkloadState"));
  }

  @Test
  void parsesAlertMessages() {
    byte[] body = """
        {
          "timestamp":"2024-07-01T12:00:01Z","version":"2","kind":"event",
          "type":"alert","origin":"swarm-controller:alpha",
          "scope":{"swarmId":"swarm-test","role":"swarm-controller","instance":"alpha"},
          "correlationId":"corr-2","idempotencyKey":"idem-2",
          "runtime":{"templateId":"tpl-1","runId":"run-1"},
          "data":{"level":"error","code":"io.out-of-data","message":"Out of data",
            "errorType":"RedisOutOfData","errorDetail":"dataset=orders",
            "logRef":"logs://example","context":{"dataset":"orders"}}
        }
        """.getBytes(UTF_8);

    ControlPlaneEventParser parser = new ControlPlaneEventParser();
    ControlPlaneEventParser.ParsedEvent parsed = parser.parse(
        "event.alert." + Alerts.TYPE + ".swarm-test.swarm-controller.alpha",
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
    ControlPlaneEventParser parser = new ControlPlaneEventParser();
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
