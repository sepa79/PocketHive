package io.pockethive.orchestrator.app;

import io.pockethive.Topology;
import io.pockethive.orchestrator.domain.ControlSignal;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ComponentControllerTest {

    @Mock
    AmqpTemplate rabbit;

    @Test
    void updateConfigPublishesControlSignal() {
        ComponentController controller = new ComponentController(rabbit, new InMemoryIdempotencyStore());
        ComponentController.ConfigUpdateRequest request =
            new ComponentController.ConfigUpdateRequest("idem", Map.of("enabled", true), null, "sw1");

        ResponseEntity<ControlResponse> response = controller.updateConfig("generator", "c1", request);

        ArgumentCaptor<ControlSignal> captor = ArgumentCaptor.forClass(ControlSignal.class);
        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("sig.config-update.generator.c1"), captor.capture());
        ControlSignal signal = captor.getValue();
        assertThat(signal.signal()).isEqualTo("config-update");
        assertThat(signal.role()).isEqualTo("generator");
        assertThat(signal.instance()).isEqualTo("c1");
        assertThat(signal.swarmId()).isEqualTo("sw1");
        assertThat(signal.idempotencyKey()).isEqualTo("idem");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().watch().successTopic()).isEqualTo("ev.ready.config-update.generator.c1");
    }

    @Test
    void configUpdateIsIdempotent() {
        ComponentController controller = new ComponentController(rabbit, new InMemoryIdempotencyStore());
        ComponentController.ConfigUpdateRequest request =
            new ComponentController.ConfigUpdateRequest("idem", Map.of(), null, null);

        ResponseEntity<ControlResponse> first = controller.updateConfig("processor", "p1", request);
        ResponseEntity<ControlResponse> second = controller.updateConfig("processor", "p1", request);

        verify(rabbit, times(1)).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("sig.config-update.processor.p1"),
            org.mockito.ArgumentMatchers.any(ControlSignal.class));
        assertThat(first.getBody()).isNotNull();
        assertThat(second.getBody()).isNotNull();
        assertThat(first.getBody().correlationId()).isEqualTo(second.getBody().correlationId());
    }
}

