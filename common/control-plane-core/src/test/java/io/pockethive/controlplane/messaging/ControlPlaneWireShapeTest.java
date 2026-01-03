package io.pockethive.controlplane.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlScope;
import io.pockethive.controlplane.schema.ControlEventsSchemaValidator;
import io.pockethive.observability.ControlPlaneJson;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.AmqpTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ControlPlaneWireShapeTest {

  @Test
  void publisherUsesCanonicalWireShape() throws Exception {
    AmqpTemplate template = mock(AmqpTemplate.class);
    AmqpControlPlanePublisher publisher = new AmqpControlPlanePublisher(template, "ph.control");

    var signal = ControlSignals.statusRequest(
        "origin",
        ControlScope.forInstance("sw1", "role", "inst"),
        "corr-1",
        null);
    publisher.publishSignal(new SignalMessage("signal.status-request.sw1.role.inst", signal));

    ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
    verify(template).convertAndSend(eq("ph.control"), eq("signal.status-request.sw1.role.inst"), payload.capture());

    assertThat(payload.getValue()).isInstanceOf(String.class);
    ObjectMapper mapper = ControlPlaneJson.mapper();
    JsonNode node = mapper.readTree(payload.getValue().toString());
    ControlEventsSchemaValidator.assertValid(node);

    JsonNode timestamp = node.get("timestamp");
    assertThat(timestamp).isNotNull();
    assertThat(timestamp.isTextual()).isTrue();
    Instant.parse(timestamp.asText());

    assertThat(node.has("data")).isTrue();
    assertThat(node.get("data").isObject()).isTrue();
    assertThat(node.has("correlationId")).isTrue();
    assertThat(node.get("correlationId").asText()).isEqualTo("corr-1");
    assertThat(node.has("idempotencyKey")).isTrue();
    assertThat(node.get("idempotencyKey").isNull()).isTrue();
  }
}
