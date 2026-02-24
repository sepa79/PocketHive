package io.pockethive.orchestrator.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmHealth;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import io.pockethive.orchestrator.domain.SwarmStatus;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.spring.ControlPlaneProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import org.slf4j.LoggerFactory;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class ControllerStatusListenerTest {
    @Mock
    SwarmRegistry registry;
    @Mock
    AmqpTemplate rabbit;
    @Mock
    ControlPlaneProperties controlPlaneProperties;

    @Test
    void updatesRegistry() {
        ControllerStatusListener listener = new ControllerStatusListener(registry, new ObjectMapper());
        Swarm swarm = org.mockito.Mockito.mock(Swarm.class);
        when(registry.find("sw1")).thenReturn(java.util.Optional.of(swarm));
        String json = """
            {
              "timestamp": "2024-01-01T00:00:00Z",
              "version": "1",
              "kind": "metric",
              "type": "status-delta",
              "origin": "inst1",
              "scope": {"swarmId":"sw1","role":"swarm-controller","instance":"inst1"},
              "correlationId": null,
              "idempotencyKey": null,
              "data": {"enabled": true, "tps": 0, "swarmStatus": "RUNNING"}
            }
            """;
        listener.handle(json, "event.metric.status-delta.sw1.swarm-controller.inst1");
        verify(registry).refresh("sw1", SwarmHealth.RUNNING);
        verify(registry).updateWorkEnabled("sw1", true);
        // RUNNING + workloadsEnabled=true should drive the registry into RUNNING
        // using the normal lifecycle helper.
        verify(registry).markStartConfirmed("sw1");
    }

    @Test
    void recoversMissingSwarmFromStatusEvent() {
        SwarmRegistry localRegistry = new SwarmRegistry();
        ControllerStatusListener listener = new ControllerStatusListener(localRegistry, new ObjectMapper());
        String json = """
            {
              "timestamp": "2024-01-01T00:00:00Z",
              "version": "1",
              "kind": "metric",
              "type": "status-delta",
              "origin": "inst1",
              "scope": {"swarmId":"sw1","role":"swarm-controller","instance":"inst1"},
              "correlationId": null,
              "idempotencyKey": null,
              "data": {"enabled": true, "tps": 0, "swarmStatus": "RUNNING"}
            }
            """;

        listener.handle(json, "event.metric.status-delta.sw1.swarm-controller.inst1");

        Swarm recovered = localRegistry.find("sw1").orElseThrow();
        assertThat(recovered.getId()).isEqualTo("sw1");
        assertThat(recovered.getInstanceId()).isEqualTo("inst1");
        assertThat(recovered.getContainerId()).isEqualTo("inst1");
        assertThat(recovered.getStatus()).isEqualTo(SwarmStatus.RUNNING);
        assertThat(recovered.getHealth()).isEqualTo(SwarmHealth.RUNNING);
        assertThat(recovered.isWorkEnabled()).isTrue();
    }

    @Test
    void updatesRegistryFromTopLevelFlags() {
        ControllerStatusListener listener = new ControllerStatusListener(registry, new ObjectMapper());
        Swarm swarm = org.mockito.Mockito.mock(Swarm.class);
        when(swarm.getStatus()).thenReturn(SwarmStatus.RUNNING);
        when(registry.find("sw1")).thenReturn(java.util.Optional.of(swarm));
        String json = """
            {
              "timestamp": "2024-01-01T00:00:00Z",
              "version": "1",
              "kind": "metric",
              "type": "status-delta",
              "origin": "inst1",
              "scope": {"swarmId":"sw1","role":"swarm-controller","instance":"inst1"},
              "correlationId": null,
              "idempotencyKey": null,
              "data": {"enabled": false, "tps": 0, "swarmStatus": "STOPPED"}
            }
            """;
        listener.handle(json, "event.metric.status-delta.sw1.swarm-controller.inst1");
        verify(registry).refresh("sw1", SwarmHealth.DEGRADED);
        verify(registry).updateWorkEnabled("sw1", false);
        verify(registry, times(2)).find("sw1");
        // STOPPED + workloadsEnabled=false should map to STOPPING -> STOPPED
        verify(registry).updateStatus("sw1", SwarmStatus.STOPPING);
        verify(registry).updateStatus("sw1", SwarmStatus.STOPPED);
    }

    @Test
    void statusLogsEmitAtDebug(CapturedOutput output) {
        ControllerStatusListener listener = new ControllerStatusListener(registry, new ObjectMapper());
        Logger logger = (Logger) LoggerFactory.getLogger(ControllerStatusListener.class);
        Level previous = logger.getLevel();
        logger.setLevel(Level.INFO);
        try {
            listener.handle("{}", "event.metric.status-delta.sw1.swarm-controller.inst1");
            assertThat(output).doesNotContain("[CTRL] RECV rk=event.metric.status-delta.sw1.swarm-controller.inst1");
        } finally {
            logger.setLevel(previous);
        }
    }

    @Test
    void handleRejectsBlankRoutingKey() {
        ControllerStatusListener listener = new ControllerStatusListener(registry, new ObjectMapper());

        assertThatCode(() -> listener.handle("{}", "  ")).doesNotThrowAnyException();
        verifyNoMoreInteractions(registry);
    }

    @Test
    void handleRejectsNullRoutingKey() {
        ControllerStatusListener listener = new ControllerStatusListener(registry, new ObjectMapper());

        assertThatCode(() -> listener.handle("{}", null)).doesNotThrowAnyException();
        verifyNoMoreInteractions(registry);
    }

    @Test
    void handleRejectsBlankPayload() {
        ControllerStatusListener listener = new ControllerStatusListener(registry, new ObjectMapper());

        assertThatCode(() -> listener.handle(" ", "event.metric.status-delta.sw1.swarm-controller.inst1"))
            .doesNotThrowAnyException();
        verifyNoMoreInteractions(registry);
    }

    @Test
    void statusFullFromControllerRequestsWorkerSnapshotsOnceWithinCooldown() {
        when(controlPlaneProperties.getExchange()).thenReturn("ph.control");
        when(controlPlaneProperties.getInstanceId()).thenReturn("orch-1");
        when(registry.find("sw1")).thenReturn(java.util.Optional.empty());
        ControllerStatusListener listener = new ControllerStatusListener(
            registry, new ObjectMapper(), rabbit, controlPlaneProperties);
        String json = """
            {
              "timestamp": "2024-01-01T00:00:00Z",
              "version": "1",
              "kind": "metric",
              "type": "status-full",
              "origin": "inst1",
              "scope": {"swarmId":"sw1","role":"swarm-controller","instance":"inst1"},
              "correlationId": null,
              "idempotencyKey": null,
              "data": {"enabled": true, "tps": 0, "swarmStatus": "RUNNING"}
            }
            """;

        listener.handle(json, "event.metric.status-full.sw1.swarm-controller.inst1");
        listener.handle(json, "event.metric.status-full.sw1.swarm-controller.inst1");

        verify(rabbit, times(1)).convertAndSend(
            eq("ph.control"),
            eq(ControlPlaneRouting.signal("status-request", "sw1", "ALL", "ALL")),
            anyString());
    }

    @Test
    void statusDeltaDoesNotRequestWorkerSnapshots() {
        when(controlPlaneProperties.getExchange()).thenReturn("ph.control");
        when(controlPlaneProperties.getInstanceId()).thenReturn("orch-1");
        ControllerStatusListener listener = new ControllerStatusListener(
            registry, new ObjectMapper(), rabbit, controlPlaneProperties);
        String json = """
            {
              "timestamp": "2024-01-01T00:00:00Z",
              "version": "1",
              "kind": "metric",
              "type": "status-delta",
              "origin": "inst1",
              "scope": {"swarmId":"sw1","role":"swarm-controller","instance":"inst1"},
              "correlationId": null,
              "idempotencyKey": null,
              "data": {"enabled": true, "tps": 0, "swarmStatus": "RUNNING"}
            }
            """;

        listener.handle(json, "event.metric.status-delta.sw1.swarm-controller.inst1");

        verify(rabbit, never()).convertAndSend(anyString(), anyString(), anyString());
    }
}
