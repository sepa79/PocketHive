package io.pockethive.postprocessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pockethive.Topology;
import io.pockethive.asyncapi.AsyncApiSchemaValidator;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PostProcessorTest {

    @Mock
    RabbitTemplate rabbit;

    @Mock
    RabbitListenerEndpointRegistry registry;

    @Mock
    MessageListenerContainer listenerContainer;

    private static final AsyncApiSchemaValidator ASYNC_API = AsyncApiSchemaValidator.loadDefault();
    private final ObjectMapper mapper = new ObjectMapper();

    private PostProcessor postProcessor;

    @BeforeEach
    void setUp() {
        lenient().when(registry.getListenerContainer("workListener")).thenReturn(listenerContainer);
        postProcessor = new PostProcessor(rabbit, new SimpleMeterRegistry(), "inst", registry);
        clearInvocations(rabbit);
    }

    @Test
    void statusRequestEmitsFullStatus() throws Exception {
        String correlationId = UUID.randomUUID().toString();
        String idempotencyKey = UUID.randomUUID().toString();
        ControlSignal signal = ControlSignal.forInstance(
            "status-request", "sw1", "postprocessor", "inst", correlationId, idempotencyKey);

        postProcessor.onControl(mapper.writeValueAsString(signal), "sig.status-request.postprocessor.inst", null);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("ev.status-full.postprocessor.inst"), payload.capture());
        JsonNode node = mapper.readTree(payload.getValue());
        List<String> errors = ASYNC_API.validate("#/components/schemas/ControlStatusFullPayload", node);
        assertThat(errors).isEmpty();
    }

    @Test
    void configUpdateAppliesEnabledAndStartsListener() throws Exception {
        Map<String, Object> args = Map.of("data", Map.of("enabled", true));
        String correlationId = UUID.randomUUID().toString();
        String idempotencyKey = UUID.randomUUID().toString();
        ControlSignal signal = ControlSignal.forInstance(
            "config-update", "sw1", "postprocessor", "inst", correlationId, idempotencyKey, args);

        postProcessor.onControl(mapper.writeValueAsString(signal), "sig.config-update.postprocessor.inst", null);

        verify(listenerContainer).start();
        Boolean enabled = (Boolean) ReflectionTestUtils.getField(postProcessor, "enabled");
        assertThat(enabled).isTrue();

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("ev.ready.config-update.postprocessor.inst"), payload.capture());
        JsonNode node = mapper.readTree(payload.getValue());
        assertThat(node.path("result").asText()).isEqualTo("success");
        assertThat(node.path("signal").asText()).isEqualTo("config-update");
        assertThat(node.path("correlationId").asText()).isEqualTo(correlationId);
        assertThat(node.path("idempotencyKey").asText()).isEqualTo(idempotencyKey);
        assertThat(node.path("scope").path("role").asText()).isEqualTo("postprocessor");
        assertThat(node.path("scope").path("instance").asText()).isEqualTo("inst");
        assertThat(node.path("scope").path("swarmId").asText()).isEqualTo("sw1");
        assertThat(node.has("args")).isFalse();
        List<String> readyErrors = ASYNC_API.validate("#/components/schemas/CommandReadyPayload", node);
        assertThat(readyErrors).isEmpty();
    }

    @Test
    void configUpdateDisablesAndStopsListener() throws Exception {
        ReflectionTestUtils.setField(postProcessor, "enabled", true);
        Map<String, Object> args = Map.of("data", Map.of("enabled", false));
        String correlationId = UUID.randomUUID().toString();
        String idempotencyKey = UUID.randomUUID().toString();
        ControlSignal signal = ControlSignal.forInstance(
            "config-update", "sw1", "postprocessor", "inst", correlationId, idempotencyKey, args);

        postProcessor.onControl(mapper.writeValueAsString(signal), "sig.config-update.postprocessor.inst", null);

        verify(listenerContainer).stop();
        Boolean enabled = (Boolean) ReflectionTestUtils.getField(postProcessor, "enabled");
        assertThat(enabled).isFalse();

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("ev.ready.config-update.postprocessor.inst"), payload.capture());
        JsonNode node = mapper.readTree(payload.getValue());
        assertThat(node.path("correlationId").asText()).isEqualTo(correlationId);
        assertThat(node.path("idempotencyKey").asText()).isEqualTo(idempotencyKey);
        assertThat(node.has("args")).isFalse();
        List<String> readyErrors = ASYNC_API.validate("#/components/schemas/CommandReadyPayload", node);
        assertThat(readyErrors).isEmpty();
    }

    @Test
    void configUpdateEmitsErrorForInvalidEnabled() throws Exception {
        Map<String, Object> args = Map.of("data", Map.of("enabled", "maybe"));
        String correlationId = UUID.randomUUID().toString();
        String idempotencyKey = UUID.randomUUID().toString();
        ControlSignal signal = ControlSignal.forInstance(
            "config-update", "sw1", "postprocessor", "inst", correlationId, idempotencyKey, args);

        postProcessor.onControl(mapper.writeValueAsString(signal), "sig.config-update.postprocessor.inst", null);

        verify(listenerContainer, never()).start();
        verify(listenerContainer, never()).stop();
        Boolean enabled = (Boolean) ReflectionTestUtils.getField(postProcessor, "enabled");
        assertThat(enabled).isFalse();

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("ev.error.config-update.postprocessor.inst"), payload.capture());
        JsonNode node = mapper.readTree(payload.getValue());
        assertThat(node.path("result").asText()).isEqualTo("error");
        assertThat(node.path("signal").asText()).isEqualTo("config-update");
        assertThat(node.path("correlationId").asText()).isEqualTo(correlationId);
        assertThat(node.path("idempotencyKey").asText()).isEqualTo(idempotencyKey);
        assertThat(node.path("scope").path("role").asText()).isEqualTo("postprocessor");
        assertThat(node.path("code").asText()).isEqualTo("IllegalArgumentException");
        assertThat(node.path("message").asText()).isNotBlank();
        List<String> errorPayload = ASYNC_API.validate("#/components/schemas/CommandErrorPayload", node);
        assertThat(errorPayload).isEmpty();
    }
}
