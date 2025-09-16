package io.pockethive.trigger;

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
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TriggerTest {

    @Mock
    RabbitTemplate rabbit;

    private final ObjectMapper mapper = new ObjectMapper();
    private TriggerConfig triggerConfig;
    private Trigger trigger;

    @BeforeEach
    void setUp() {
        triggerConfig = new TriggerConfig();
        triggerConfig.setActionType("");
        triggerConfig.setCommand("echo test");
        triggerConfig.setUrl("https://example.test");
        triggerConfig.setMethod("GET");
        triggerConfig.setBody("{}");
        triggerConfig.setHeaders(new LinkedHashMap<>());
        trigger = new Trigger(rabbit, "inst", triggerConfig, mapper);
        clearInvocations(rabbit);
    }

    @Test
    void statusRequestEmitsFullStatus() throws Exception {
        ControlSignal signal = ControlSignal.forInstance(
            "status-request", "sw1", "trigger", "inst", "corr", "idem");

        trigger.onControl(mapper.writeValueAsString(signal), "sig.status-request.trigger.inst", null);

        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("ev.status-full.trigger.inst"), anyString());
    }

    @Test
    void configUpdateAppliesArgsAndEmitsConfirmation() throws Exception {
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("X-Test", "123");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", true);
        data.put("intervalMs", 2500);
        data.put("actionType", "noop");
        data.put("command", "echo hi");
        data.put("url", "https://api.example");
        data.put("method", "post");
        data.put("body", "{\"msg\":\"hi\"}");
        data.put("headers", headers);
        Map<String, Object> args = Map.of("data", data);
        ControlSignal signal = ControlSignal.forInstance(
            "config-update", "sw1", "trigger", "inst", "corr", "idem", args);

        trigger.onControl(mapper.writeValueAsString(signal), "sig.config-update.trigger.inst", null);

        Boolean enabled = (Boolean) ReflectionTestUtils.getField(trigger, "enabled");
        assertThat(enabled).isTrue();
        assertThat(triggerConfig.getIntervalMs()).isEqualTo(2500);
        assertThat(triggerConfig.getActionType()).isEqualTo("noop");
        assertThat(triggerConfig.getCommand()).isEqualTo("echo hi");
        assertThat(triggerConfig.getUrl()).isEqualTo("https://api.example");
        assertThat(triggerConfig.getMethod()).isEqualTo("post");
        assertThat(triggerConfig.getBody()).isEqualTo("{\"msg\":\"hi\"}");
        assertThat(triggerConfig.getHeaders()).containsEntry("X-Test", "123");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("ev.ready.config-update.trigger.inst"), payload.capture());
        JsonNode node = mapper.readTree(payload.getValue());
        assertThat(node.path("result").asText()).isEqualTo("success");
        assertThat(node.path("signal").asText()).isEqualTo("config-update");
        assertThat(node.path("correlationId").asText()).isEqualTo("corr");
        assertThat(node.path("idempotencyKey").asText()).isEqualTo("idem");
        assertThat(node.path("scope").path("role").asText()).isEqualTo("trigger");
        assertThat(node.path("scope").path("instance").asText()).isEqualTo("inst");
        assertThat(node.path("scope").path("swarmId").asText()).isEqualTo("sw1");
        assertThat(node.path("args").path("data").path("enabled").asBoolean()).isTrue();

        verify(rabbit, never()).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("ev.error.config-update.trigger.inst"), anyString());
    }

    @Test
    void configUpdateEmitsErrorWhenIntervalInvalid() throws Exception {
        Map<String, Object> data = Map.of("intervalMs", "oops");
        Map<String, Object> args = Map.of("data", data);
        ControlSignal signal = ControlSignal.forInstance(
            "config-update", "sw1", "trigger", "inst", "corr", "idem", args);

        trigger.onControl(mapper.writeValueAsString(signal), "sig.config-update.trigger.inst", null);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("ev.error.config-update.trigger.inst"), payload.capture());
        JsonNode node = mapper.readTree(payload.getValue());
        assertThat(node.path("result").asText()).isEqualTo("error");
        assertThat(node.path("signal").asText()).isEqualTo("config-update");
        assertThat(node.path("correlationId").asText()).isEqualTo("corr");
        assertThat(node.path("idempotencyKey").asText()).isEqualTo("idem");
        assertThat(node.path("code").asText()).isEqualTo("IllegalArgumentException");

        Boolean enabled = (Boolean) ReflectionTestUtils.getField(trigger, "enabled");
        assertThat(enabled).isFalse();
        assertThat(triggerConfig.getIntervalMs()).isZero();

        verify(rabbit, never()).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("ev.ready.config-update.trigger.inst"), anyString());
    }

    @Test
    void singleRequestTriggersOnceAndEmitsConfirmation() throws Exception {
        Map<String, Object> args = Map.of("singleRequest", true);
        ControlSignal signal = ControlSignal.forInstance(
            "config-update", "sw1", "trigger", "inst", "corr", "idem", args);

        trigger.onControl(mapper.writeValueAsString(signal), "sig.config-update.trigger.inst", null);

        AtomicLong counter = (AtomicLong) ReflectionTestUtils.getField(trigger, "counter");
        assertThat(counter).isNotNull();
        assertThat(counter.get()).isEqualTo(1L);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("ev.ready.config-update.trigger.inst"), payload.capture());
        JsonNode node = mapper.readTree(payload.getValue());
        assertThat(node.path("args").path("singleRequest").asBoolean()).isTrue();
    }
}
