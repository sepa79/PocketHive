package io.pockethive.orchestrator.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.pockethive.orchestrator.domain.SwarmHealth;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import org.slf4j.LoggerFactory;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class ControllerStatusListenerTest {
    @Mock
    SwarmRegistry registry;

    @Test
    void updatesRegistry() {
        ControllerStatusListener listener = new ControllerStatusListener(registry, new ObjectMapper());
        String json = "{\"swarmId\":\"sw1\",\"data\":{\"swarmStatus\":\"RUNNING\",\"state\":{\"workloads\":{\"enabled\":true},\"controller\":{\"enabled\":false}}}}";
        listener.handle(json, "ev.status-delta.sw1.swarm-controller.inst1");
        verify(registry).refresh("sw1", SwarmHealth.RUNNING);
        verify(registry).updateWorkEnabled("sw1", true);
        verify(registry).updateControllerEnabled("sw1", false);
    }

    @Test
    void updatesRegistryFromTopLevelFlags() {
        ControllerStatusListener listener = new ControllerStatusListener(registry, new ObjectMapper());
        String json = "{\"swarmId\":\"sw1\",\"data\":{\"swarmStatus\":\"STOPPED\",\"workloadsEnabled\":false,\"controllerEnabled\":true}}";
        listener.handle(json, "ev.status-delta.sw1.swarm-controller.inst1");
        verify(registry).refresh("sw1", SwarmHealth.DEGRADED);
        verify(registry).updateWorkEnabled("sw1", false);
        verify(registry).updateControllerEnabled("sw1", true);
    }

    @Test
    void statusLogsEmitAtDebug(CapturedOutput output) {
        ControllerStatusListener listener = new ControllerStatusListener(registry, new ObjectMapper());
        Logger logger = (Logger) LoggerFactory.getLogger(ControllerStatusListener.class);
        Level previous = logger.getLevel();
        logger.setLevel(Level.INFO);
        try {
            listener.handle("{}", "ev.status-delta.sw1.swarm-controller.inst1");
            assertThat(output).doesNotContain("[CTRL] RECV rk=ev.status-delta.sw1.swarm-controller.inst1");
        } finally {
            logger.setLevel(previous);
        }
    }

    @Test
    void handleRejectsBlankRoutingKey() {
        ControllerStatusListener listener = new ControllerStatusListener(registry, new ObjectMapper());

        assertThatThrownBy(() -> listener.handle("{}", "  "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("routing key");
    }

    @Test
    void handleRejectsNullRoutingKey() {
        ControllerStatusListener listener = new ControllerStatusListener(registry, new ObjectMapper());

        assertThatThrownBy(() -> listener.handle("{}", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("routing key");
    }

    @Test
    void handleRejectsBlankPayload() {
        ControllerStatusListener listener = new ControllerStatusListener(registry, new ObjectMapper());

        assertThatThrownBy(() -> listener.handle(" ", "ev.status-delta.sw1.swarm-controller.inst1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("payload");
    }
}
