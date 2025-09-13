package io.pockethive.orchestrator.app;

import io.pockethive.Topology;
import io.pockethive.orchestrator.domain.SwarmPlan;
import io.pockethive.orchestrator.domain.SwarmPlanRegistry;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.AmqpTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SwarmSignalListenerTest {
    @Mock
    AmqpTemplate rabbit;

    @Test
    void dispatchesTemplateWhenControllerReady() {
        SwarmPlanRegistry registry = new SwarmPlanRegistry();
        SwarmPlan plan = new SwarmPlan("sw1", java.util.List.of(
            new SwarmPlan.Bee("generator", "img", new SwarmPlan.Work("in", "out"))));
        registry.register("inst1", plan);
        SwarmSignalListener listener = new SwarmSignalListener(rabbit, registry, new SwarmRegistry(), new ObjectMapper(), "inst0");
        reset(rabbit);

        listener.handle("", "ev.ready.swarm-controller.inst1");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("sig.swarm-template.sw1"), captor.capture());
        assertThat(captor.getValue()).contains("\"id\":\"sw1\"");
        assertThat(registry.find("inst1")).isEmpty();
    }

    @Test
    void ignoresNonControllerReadyEvents() {
        SwarmPlanRegistry registry = new SwarmPlanRegistry();
        SwarmSignalListener listener = new SwarmSignalListener(rabbit, registry, new SwarmRegistry(), new ObjectMapper(), "inst0");
        reset(rabbit);

        listener.handle("", "ev.ready.other-controller.inst1");

        verifyNoInteractions(rabbit);
    }
}
