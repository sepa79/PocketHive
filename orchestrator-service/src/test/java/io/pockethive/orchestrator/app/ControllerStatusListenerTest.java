package io.pockethive.orchestrator.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.orchestrator.domain.SwarmHealth;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ControllerStatusListenerTest {
    @Mock
    SwarmRegistry registry;

    @Test
    void updatesRegistry() {
        ControllerStatusListener listener = new ControllerStatusListener(registry, new ObjectMapper());
        String json = "{\"swarmId\":\"sw1\",\"data\":{\"swarmStatus\":\"RUNNING\"}}";
        listener.handle(json, "ev.status-delta.swarm-controller.inst1");
        verify(registry).refresh("sw1", SwarmHealth.RUNNING);
    }
}
