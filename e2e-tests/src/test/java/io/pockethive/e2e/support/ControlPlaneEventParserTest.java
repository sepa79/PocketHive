package io.pockethive.e2e.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.pockethive.control.CommandState;
import io.pockethive.control.Confirmation;
import io.pockethive.control.ConfirmationScope;
import io.pockethive.control.ErrorConfirmation;
import io.pockethive.control.ReadyConfirmation;

class ControlPlaneEventParserTest {

  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  @Test
  void parsesReadyConfirmations() throws Exception {
    ReadyConfirmation confirmation = new ReadyConfirmation(
        Instant.now(),
        "corr-1",
        "idem-1",
        "swarm-start",
        ConfirmationScope.forSwarm("swarm-test"),
        new CommandState("Running", true, Map.of("workloads", Map.of("enabled", true)))
    );
    byte[] body = mapper.writeValueAsBytes(confirmation);

    ControlPlaneEventParser parser = new ControlPlaneEventParser(mapper);
    Confirmation parsed = parser.parse("ev.ready.swarm-start.swarm-test.swarm-controller.alpha", body);

    ReadyConfirmation ready = assertInstanceOf(ReadyConfirmation.class, parsed);
    assertEquals("corr-1", ready.correlationId());
    assertEquals("swarm-start", ready.signal());
    assertNotNull(ready.state());
  }

  @Test
  void parsesErrorConfirmations() throws Exception {
    ErrorConfirmation confirmation = new ErrorConfirmation(
        Instant.now(),
        "corr-2",
        "idem-2",
        "swarm-stop",
        ConfirmationScope.forSwarm("swarm-test"),
        new CommandState("Stopped", false, null),
        "stop",
        "IllegalStateException",
        "boom",
        Boolean.FALSE,
        null
    );
    byte[] body = mapper.writeValueAsBytes(confirmation);

    ControlPlaneEventParser parser = new ControlPlaneEventParser(mapper);
    Confirmation parsed = parser.parse("ev.error.swarm-stop.swarm-test.swarm-controller.alpha", body);

    ErrorConfirmation error = assertInstanceOf(ErrorConfirmation.class, parsed);
    assertEquals("corr-2", error.correlationId());
    assertEquals("swarm-stop", error.signal());
    assertEquals("boom", error.message());
  }
}
