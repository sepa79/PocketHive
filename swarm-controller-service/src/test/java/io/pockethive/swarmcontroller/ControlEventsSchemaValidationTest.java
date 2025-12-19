package io.pockethive.swarmcontroller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.swarmcontroller.testing.ControlEventsSchemaValidator;
import java.util.Map;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

@ExtendWith(MockitoExtension.class)
final class ControlEventsSchemaValidationTest {

  private static final String CONTROL_EXCHANGE = "ph.control";
  private static final String SWARM_ID = "default";

  @Mock
  private SwarmLifecycle lifecycle;

  @Mock
  private RabbitTemplate rabbit;

  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  @Test
  void swarmControllerStatusPayloadsValidateAgainstSchema() throws Exception {
    when(lifecycle.getStatus()).thenReturn(SwarmStatus.RUNNING);
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(0, 0, 0, 0, java.time.Instant.now()));
    when(lifecycle.snapshotQueueStats()).thenReturn(
        Map.of("ph.default.work.in", new QueueStats(7L, 3, OptionalLong.of(42L))));

    SwarmSignalListener listener = new SwarmSignalListener(
        lifecycle,
        rabbit,
        "inst",
        mapper,
        SwarmControllerTestProperties.defaults(),
        io.pockethive.swarmcontroller.runtime.SwarmJournal.noop(),
        "");

    ArgumentCaptor<String> fullPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(
        eq(CONTROL_EXCHANGE),
        startsWith("event.metric.status-full." + SWARM_ID + ".swarm-controller.inst"),
        fullPayload.capture());
    ControlEventsSchemaValidator.assertValid(fullPayload.getValue());

    reset(rabbit);

    listener.status();

    ArgumentCaptor<String> deltaPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(
        eq(CONTROL_EXCHANGE),
        startsWith("event.metric.status-delta." + SWARM_ID + ".swarm-controller.inst"),
        deltaPayload.capture());
    ControlEventsSchemaValidator.assertValid(deltaPayload.getValue());
  }
}

