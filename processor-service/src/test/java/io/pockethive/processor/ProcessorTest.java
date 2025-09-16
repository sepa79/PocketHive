package io.pockethive.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pockethive.Topology;
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

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcessorTest {

    @Mock
    RabbitTemplate rabbit;

    @Mock
    RabbitListenerEndpointRegistry listenerRegistry;

    @Mock
    MessageListenerContainer workContainer;

    private final ObjectMapper mapper = new ObjectMapper();
    private SimpleMeterRegistry meterRegistry;
    private Processor processor;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        processor = new Processor(rabbit, meterRegistry, "inst", "http://initial", listenerRegistry);
        clearInvocations(rabbit, listenerRegistry, workContainer);
    }

    @Test
    void statusRequestEmitsFullStatus() throws Exception {
        ControlSignal signal = ControlSignal.forInstance(
            "status-request", "sw1", "processor", "inst", "corr", "idem");

        processor.onControl(mapper.writeValueAsString(signal), "sig.status-request.processor.inst", null);

        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("ev.status-full.processor.inst"), anyString());
        verifyNoMoreInteractions(rabbit);
    }

    @Test
    void configUpdateAppliesArgsAndEmitsConfirmation() throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", true);
        data.put("baseUrl", "http://next");
        Map<String, Object> args = Map.of("data", data);
        ControlSignal signal = ControlSignal.forInstance(
            "config-update", "sw1", "processor", "inst", "corr", "idem", args);

        when(listenerRegistry.getListenerContainer("workListener")).thenReturn(workContainer);
        processor.onControl(mapper.writeValueAsString(signal), "sig.config-update.processor.inst", null);

        Boolean enabled = (Boolean) ReflectionTestUtils.getField(processor, "enabled");
        assertThat(enabled).isTrue();
        String baseUrl = (String) ReflectionTestUtils.getField(processor, "baseUrl");
        assertThat(baseUrl).isEqualTo("http://next");
        verify(workContainer).start();

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("ev.ready.config-update.processor.inst"), payload.capture());
        JsonNode node = mapper.readTree(payload.getValue());
        assertThat(node.path("result").asText()).isEqualTo("success");
        assertThat(node.path("signal").asText()).isEqualTo("config-update");
        assertThat(node.path("correlationId").asText()).isEqualTo("corr");
        assertThat(node.path("idempotencyKey").asText()).isEqualTo("idem");
        assertThat(node.path("scope").path("role").asText()).isEqualTo("processor");
        assertThat(node.path("scope").path("instance").asText()).isEqualTo("inst");
        assertThat(node.path("scope").path("swarmId").asText()).isEqualTo("sw1");
        assertThat(node.has("args")).isFalse();
        verify(rabbit, never()).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("ev.error.config-update.processor.inst"), anyString());
    }

    @Test
    void configUpdateErrorEmitsErrorConfirmation() throws Exception {
        Map<String, Object> args = Map.of("data", Map.of("enabled", "oops"));
        ControlSignal signal = ControlSignal.forInstance(
            "config-update", "sw1", "processor", "inst", "corr", "idem", args);

        processor.onControl(mapper.writeValueAsString(signal), "sig.config-update.processor.inst", null);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("ev.error.config-update.processor.inst"), payload.capture());
        JsonNode node = mapper.readTree(payload.getValue());
        assertThat(node.path("result").asText()).isEqualTo("error");
        assertThat(node.path("signal").asText()).isEqualTo("config-update");
        assertThat(node.path("correlationId").asText()).isEqualTo("corr");
        assertThat(node.path("idempotencyKey").asText()).isEqualTo("idem");
        assertThat(node.path("scope").path("role").asText()).isEqualTo("processor");
        assertThat(node.path("code").asText()).isEqualTo("IllegalArgumentException");

        Boolean enabled = (Boolean) ReflectionTestUtils.getField(processor, "enabled");
        assertThat(enabled).isFalse();
        verify(workContainer, never()).start();
        verify(rabbit, never()).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("ev.ready.config-update.processor.inst"), anyString());
    }
}
