package io.pockethive.orchestrator.app;

import io.pockethive.Topology;
import io.pockethive.orchestrator.domain.ControlSignal;
import io.pockethive.orchestrator.domain.SwarmCreateTracker;
import io.pockethive.orchestrator.domain.Swarm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SwarmControllerTest {
    @Mock
    AmqpTemplate rabbit;
    @Mock
    ContainerLifecycleManager lifecycle;

    @Test
    void startPublishesControlSignal() {
        SwarmController ctrl = new SwarmController(rabbit, lifecycle, new SwarmCreateTracker());
        SwarmController.ControlRequest req = new SwarmController.ControlRequest("idem", null);

        ResponseEntity<SwarmController.ControlResponse> resp = ctrl.start("sw1", req);

        ArgumentCaptor<ControlSignal> captor = ArgumentCaptor.forClass(ControlSignal.class);
        verify(rabbit).convertAndSend(eq(Topology.CONTROL_EXCHANGE), eq("sig.swarm-start.sw1"), captor.capture());
        ControlSignal sig = captor.getValue();
        assertThat(sig.signal()).isEqualTo("swarm-start");
        assertThat(sig.swarmId()).isEqualTo("sw1");
        assertThat(sig.idempotencyKey()).isEqualTo("idem");
        assertThat(resp.getBody().watch().successTopic()).isEqualTo("ev.ready.swarm-start.sw1");
    }

    @Test
    void createRegistersPending() {
        SwarmCreateTracker tracker = new SwarmCreateTracker();
        when(lifecycle.startSwarm(eq("sw1"), anyString())).thenReturn(new Swarm("sw1", "instA", "c1"));
        SwarmController ctrl = new SwarmController(rabbit, lifecycle, tracker);
        SwarmController.ControlRequest req = new SwarmController.ControlRequest("idem", null);

        ctrl.create("sw1", req);

        assertThat(tracker.remove("instA")).isPresent();
    }
}
