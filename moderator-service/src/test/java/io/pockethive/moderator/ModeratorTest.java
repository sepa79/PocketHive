package io.pockethive.moderator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.Topology;
import io.pockethive.asyncapi.AsyncApiSchemaValidator;
import io.pockethive.control.CommandTarget;
import io.pockethive.control.ControlSignal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModeratorTest {

    @Mock
    RabbitTemplate rabbit;

    @Mock
    RabbitListenerEndpointRegistry registry;

    @Mock
    MessageListenerContainer container;

    private static final AsyncApiSchemaValidator ASYNC_API = AsyncApiSchemaValidator.loadDefault();
    private final ObjectMapper mapper = new ObjectMapper();

    private Moderator moderator;

    @BeforeEach
    void setUp() {
        moderator = new Moderator(rabbit, "inst", registry, mapper);
        clearInvocations(rabbit);
    }

    @Test
    void statusRequestEmitsFullStatus() throws Exception {
        String correlationId = UUID.randomUUID().toString();
        String idempotencyKey = UUID.randomUUID().toString();
        ControlSignal signal = ControlSignal.forInstance(
            "status-request", "sw1", "moderator", "inst", correlationId, idempotencyKey);

        moderator.onControl(mapper.writeValueAsString(signal), "sig.status-request.moderator.inst", null);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("ev.status-full.moderator.inst"), payload.capture());
        JsonNode node = mapper.readTree(payload.getValue());
        List<String> errors = ASYNC_API.validate("#/components/schemas/ControlStatusFullPayload", node);
        assertThat(errors).isEmpty();
    }

    @Test
    void configUpdateAppliesEnabledAndEmitsConfirmation() throws Exception {
        Map<String, Object> args = Map.of("data", Map.of("enabled", true));
        String correlationId = UUID.randomUUID().toString();
        String idempotencyKey = UUID.randomUUID().toString();
        ControlSignal signal = ControlSignal.forInstance(
            "config-update", "sw1", "moderator", "inst", correlationId, idempotencyKey,
            CommandTarget.INSTANCE, args);

        when(registry.getListenerContainer("workListener")).thenReturn(container);

        moderator.onControl(mapper.writeValueAsString(signal), "sig.config-update.moderator.inst", null);

        Boolean enabled = (Boolean) ReflectionTestUtils.getField(moderator, "enabled");
        assertThat(enabled).isTrue();
        verify(container).start();

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("ev.ready.config-update.moderator.inst"), payload.capture());

        JsonNode node = mapper.readTree(payload.getValue());
        assertThat(node.path("result").asText()).isEqualTo("success");
        assertThat(node.path("signal").asText()).isEqualTo("config-update");
        assertThat(node.path("correlationId").asText()).isEqualTo(correlationId);
        assertThat(node.path("idempotencyKey").asText()).isEqualTo(idempotencyKey);
        assertThat(node.path("scope").path("role").asText()).isEqualTo("moderator");
        assertThat(node.path("scope").path("instance").asText()).isEqualTo("inst");
        assertThat(node.path("scope").path("swarmId").asText()).isEqualTo("sw1");
        assertThat(node.path("state").path("scope").isMissingNode()).isTrue();
        assertThat(node.path("state").path("enabled").asBoolean()).isTrue();
        assertThat(node.has("args")).isFalse();
        List<String> readyErrors = ASYNC_API.validate("#/components/schemas/CommandReadyPayload", node);
        assertThat(readyErrors).isEmpty();
    }

    @Test
    void configUpdateWithInvalidEnabledEmitsError() throws Exception {
        Map<String, Object> args = Map.of("enabled", 1);
        String correlationId = UUID.randomUUID().toString();
        String idempotencyKey = UUID.randomUUID().toString();
        ControlSignal signal = ControlSignal.forInstance(
            "config-update", "sw1", "moderator", "inst", correlationId, idempotencyKey,
            CommandTarget.INSTANCE, args);

        moderator.onControl(mapper.writeValueAsString(signal), "sig.config-update.moderator.inst", null);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("ev.error.config-update.moderator.inst"), payload.capture());
        JsonNode node = mapper.readTree(payload.getValue());
        assertThat(node.path("result").asText()).isEqualTo("error");
        assertThat(node.path("signal").asText()).isEqualTo("config-update");
        assertThat(node.path("correlationId").asText()).isEqualTo(correlationId);
        assertThat(node.path("idempotencyKey").asText()).isEqualTo(idempotencyKey);
        assertThat(node.path("scope").path("role").asText()).isEqualTo("moderator");
        assertThat(node.path("scope").path("instance").asText()).isEqualTo("inst");
        assertThat(node.path("code").asText()).isEqualTo("IllegalArgumentException");
        assertThat(node.path("message").asText()).isNotBlank();
        assertThat(node.path("state").path("scope").isMissingNode()).isTrue();
        assertThat(node.path("state").path("enabled").asBoolean()).isFalse();
        List<String> errorPayload = ASYNC_API.validate("#/components/schemas/CommandErrorPayload", node);
        assertThat(errorPayload).isEmpty();

        verify(container, never()).start();
        verify(container, never()).stop();
        Boolean enabled = (Boolean) ReflectionTestUtils.getField(moderator, "enabled");
        assertThat(enabled).isFalse();
    }
}
