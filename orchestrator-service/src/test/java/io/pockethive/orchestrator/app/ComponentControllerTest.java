package io.pockethive.orchestrator.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.Topology;
import io.pockethive.control.ControlSignal;
import io.pockethive.orchestrator.infra.InMemoryIdempotencyStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ComponentControllerTest {

    @Mock
    AmqpTemplate rabbit;

    private final ObjectMapper mapper = new JacksonConfiguration().objectMapper();

    @Test
    void updateConfigPublishesControlSignal() throws Exception {
        ComponentController controller = new ComponentController(rabbit, new InMemoryIdempotencyStore(), mapper);
        ComponentController.ConfigUpdateRequest request =
            new ComponentController.ConfigUpdateRequest("idem", Map.of("enabled", true), null, "sw1");

        ResponseEntity<ControlResponse> response = controller.updateConfig("generator", "c1", request);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("sig.config-update.generator.c1"), captor.capture());
        ControlSignal signal = mapper.readValue(captor.getValue(), ControlSignal.class);
        assertThat(signal.signal()).isEqualTo("config-update");
        assertThat(signal.role()).isEqualTo("generator");
        assertThat(signal.instance()).isEqualTo("c1");
        assertThat(signal.swarmId()).isEqualTo("sw1");
        assertThat(signal.idempotencyKey()).isEqualTo("idem");
        assertThat(signal.args()).isNotNull();
        assertThat(signal.args()).containsKey("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) signal.args().get("data");
        assertThat(data).containsEntry("enabled", true);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().watch().successTopic()).isEqualTo("ev.ready.config-update.generator.c1");
    }

    @Test
    void configUpdateIsIdempotent() {
        ComponentController controller = new ComponentController(rabbit, new InMemoryIdempotencyStore(), mapper);
        ComponentController.ConfigUpdateRequest request =
            new ComponentController.ConfigUpdateRequest("idem", Map.of(), null, null);

        ResponseEntity<ControlResponse> first = controller.updateConfig("processor", "p1", request);
        ResponseEntity<ControlResponse> second = controller.updateConfig("processor", "p1", request);

        verify(rabbit, times(1)).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("sig.config-update.processor.p1"),
            anyString());
        assertThat(first.getBody()).isNotNull();
        assertThat(second.getBody()).isNotNull();
        assertThat(first.getBody().correlationId()).isEqualTo(second.getBody().correlationId());
    }
}

