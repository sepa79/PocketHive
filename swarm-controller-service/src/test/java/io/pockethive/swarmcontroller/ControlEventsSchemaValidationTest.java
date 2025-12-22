package io.pockethive.swarmcontroller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.swarmcontroller.testing.ControlEventsSchemaValidator;
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
    String fullJson = fullPayload.getValue();
    ControlEventsSchemaValidator.assertValid(fullJson);
    JsonNode full = mapper.readTree(fullJson);
    JsonNode fullData = full.path("data");
    assertThat(fullData.has("startedAt")).isTrue();
    assertThat(fullData.has("config")).isTrue();
    assertThat(fullData.has("io")).isTrue();
    assertThat(fullData.path("context").path("workers").isArray()).isTrue();

    reset(rabbit);

    listener.status();

    ArgumentCaptor<String> deltaPayload = ArgumentCaptor.forClass(String.class);
    verify(rabbit).convertAndSend(
        eq(CONTROL_EXCHANGE),
        startsWith("event.metric.status-delta." + SWARM_ID + ".swarm-controller.inst"),
        deltaPayload.capture());
    String deltaJson = deltaPayload.getValue();
    ControlEventsSchemaValidator.assertValid(deltaJson);
    JsonNode delta = mapper.readTree(deltaJson);
    JsonNode deltaData = delta.path("data");
    assertThat(deltaData.has("startedAt")).isFalse();
    assertThat(deltaData.has("config")).isFalse();
    assertThat(deltaData.has("io")).isFalse();
    assertThat(deltaData.path("context").path("workers").isMissingNode()).isTrue();
  }
}
