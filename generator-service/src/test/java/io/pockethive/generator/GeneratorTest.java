package io.pockethive.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.Topology;
import io.pockethive.control.ControlSignal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class GeneratorTest {

    @Mock
    RabbitTemplate rabbit;

    private final ObjectMapper mapper = new ObjectMapper();
    private MessageConfig messageConfig;
    private Generator generator;

    @BeforeEach
    void setUp() {
        messageConfig = new MessageConfig();
        messageConfig.setPath("/default");
        messageConfig.setMethod("GET");
        messageConfig.setBody("{}");
        messageConfig.setHeaders(new LinkedHashMap<>());
        generator = new Generator(rabbit, "inst", messageConfig, mapper);
        clearInvocations(rabbit);
    }

    @Test
    void statusRequestEmitsFullStatus() throws Exception {
        ControlSignal signal = ControlSignal.forInstance(
            "status-request", "sw1", "generator", "inst", "corr", "idem");

        generator.onControl(mapper.writeValueAsString(signal), "sig.status-request.generator.inst", null);

        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("ev.status-full.generator.inst"), anyString());
    }

    @Test
    void configUpdateAppliesArgsAndEmitsConfirmation() throws Exception {
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("X-Test", "123");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", true);
        data.put("ratePerSec", 5.5);
        data.put("singleRequest", true);
        data.put("path", "/next");
        data.put("method", "POST");
        data.put("body", "{\"msg\":\"hi\"}");
        data.put("headers", headers);
        Map<String, Object> args = Map.of("data", data);
        ControlSignal signal = ControlSignal.forInstance(
            "config-update", "sw1", "generator", "inst", "corr", "idem", args);

        generator.onControl(mapper.writeValueAsString(signal), "sig.config-update.generator.inst", null);

        Boolean enabled = (Boolean) ReflectionTestUtils.getField(generator, "enabled");
        assertThat(enabled).isTrue();
        Double ratePerSec = (Double) ReflectionTestUtils.getField(generator, "ratePerSec");
        assertThat(ratePerSec).isEqualTo(5.5);
        assertThat(messageConfig.getPath()).isEqualTo("/next");
        assertThat(messageConfig.getMethod()).isEqualTo("POST");
        assertThat(messageConfig.getBody()).isEqualTo("{\"msg\":\"hi\"}");
        assertThat(messageConfig.getHeaders()).containsEntry("X-Test", "123");

        verify(rabbit).convertAndSend(eq(Topology.EXCHANGE), eq(Topology.GEN_QUEUE), isA(Message.class));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("ev.ready.config-update.generator.inst"), payload.capture());
        JsonNode node = mapper.readTree(payload.getValue());
        assertThat(node.path("result").asText()).isEqualTo("success");
        assertThat(node.path("signal").asText()).isEqualTo("config-update");
        assertThat(node.path("correlationId").asText()).isEqualTo("corr");
        assertThat(node.path("idempotencyKey").asText()).isEqualTo("idem");
        assertThat(node.path("scope").path("role").asText()).isEqualTo("generator");
        assertThat(node.path("scope").path("instance").asText()).isEqualTo("inst");
        assertThat(node.path("scope").path("swarmId").asText()).isEqualTo("sw1");
        assertThat(node.path("args").path("data").path("enabled").asBoolean()).isTrue();
    }

    @Test
    void configUpdateEmitsErrorWhenRateIsInvalid() throws Exception {
        Map<String, Object> data = Map.of("ratePerSec", "oops");
        Map<String, Object> args = Map.of("data", data);
        ControlSignal signal = ControlSignal.forInstance(
            "config-update", "sw1", "generator", "inst", "corr", "idem", args);

        generator.onControl(mapper.writeValueAsString(signal), "sig.config-update.generator.inst", null);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("ev.error.config-update.generator.inst"), payload.capture());
        JsonNode node = mapper.readTree(payload.getValue());
        assertThat(node.path("result").asText()).isEqualTo("error");
        assertThat(node.path("signal").asText()).isEqualTo("config-update");
        assertThat(node.path("correlationId").asText()).isEqualTo("corr");
        assertThat(node.path("idempotencyKey").asText()).isEqualTo("idem");
        assertThat(node.path("scope").path("role").asText()).isEqualTo("generator");
        assertThat(node.path("scope").path("instance").asText()).isEqualTo("inst");
        assertThat(node.path("code").asText()).isEqualTo("IllegalArgumentException");

        verify(rabbit, never()).convertAndSend(eq(Topology.EXCHANGE), eq(Topology.GEN_QUEUE), isA(Message.class));
        verify(rabbit, never()).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("ev.ready.config-update.generator.inst"), anyString());

        Double ratePerSec = (Double) ReflectionTestUtils.getField(generator, "ratePerSec");
        assertThat(ratePerSec).isEqualTo(0.0);
    }
}

