package io.pockethive.controlplane.spring;

import io.pockethive.controlplane.messaging.ControlSignals;
import io.pockethive.controlplane.messaging.SignalMessage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlScope;
import io.pockethive.controlplane.schema.ControlEventsSchemaValidator;
import io.pockethive.observability.ControlPlaneJson;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import io.pockethive.rabbit.api.RabbitPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ControlPlaneWireShapeTest {

  @Test
  void publisherUsesCanonicalWireShape() throws Exception {
    RabbitPublisher template = mock(RabbitPublisher.class);
    AmqpControlPlanePublisher publisher = new AmqpControlPlanePublisher(
        template, "ph.control", io.pockethive.controlplane.codec.ControlPlaneCodec.create());

	    var signal = ControlSignals.statusRequest(
	        "origin",
	        ControlScope.forInstance("sw1", "role", "inst"),
	        "corr-1",
	        "idem-1");
	    publisher.publishSignal(new SignalMessage("signal.status-request.sw1.role.inst", signal));

    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    verify(template).sendText(eq("ph.control"), eq("signal.status-request.sw1.role.inst"), payload.capture());

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
    assertThat(node.get("idempotencyKey").asText()).isEqualTo("idem-1");
  }
}
