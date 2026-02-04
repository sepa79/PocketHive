package io.pockethive.orchestrator.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmLifecycleStatus;
import io.pockethive.orchestrator.domain.HiveJournal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.slf4j.LoggerFactory;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class ControllerStatusListenerTest {
    @Mock
    SwarmStore store;

    @Mock
    ControlPlaneStatusRequestPublisher statusRequests;

    @Mock
    SwarmSignalListener swarmSignals;

    @Test
    void updatesRegistry() throws Exception {
	        Swarm swarm = new Swarm("sw1", "inst1", "c1", "run-1");
		        swarm.updateControllerStatusFull(new ObjectMapper().readTree("{\"data\":{}}"), Instant.now());
		        when(store.find("sw1")).thenReturn(Optional.of(swarm));
		        ControllerStatusListener listener =
		            new ControllerStatusListener(store, new ObjectMapper(), statusRequests, swarmSignals, HiveJournal.noop());
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
              "data": {"enabled": true, "context": {"swarmStatus": "RUNNING"}}
            }
	            """;
        listener.handle(json, "event.metric.status-delta.sw1.swarm-controller.inst1");
        // RUNNING + workloadsEnabled=true should drive the registry into RUNNING
        // using the normal lifecycle helper.
        verify(store).markStartConfirmed("sw1");
    }

    @Test
    void updatesRegistryFromTopLevelFlags() throws Exception {
	        Swarm swarm = new Swarm("sw1", "inst1", "c1", "run-1");
		        swarm.updateControllerStatusFull(new ObjectMapper().readTree("{\"data\":{}}"), Instant.now());
		        when(store.find("sw1")).thenReturn(Optional.of(swarm));
		        ControllerStatusListener listener =
		            new ControllerStatusListener(store, new ObjectMapper(), statusRequests, swarmSignals, HiveJournal.noop());
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
              "data": {"enabled": false, "context": {"swarmStatus": "STOPPED"}}
            }
	            """;
        listener.handle(json, "event.metric.status-delta.sw1.swarm-controller.inst1");
        // STOPPED + workloadsEnabled=false should map to STOPPING -> STOPPED
        verify(store).updateStatus("sw1", SwarmLifecycleStatus.STOPPING);
        verify(store).updateStatus("sw1", SwarmLifecycleStatus.STOPPED);
    }

    @Test
		    void statusLogsEmitAtDebug(CapturedOutput output) {
		        ControllerStatusListener listener =
		            new ControllerStatusListener(store, new ObjectMapper(), statusRequests, swarmSignals, HiveJournal.noop());
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
		        ControllerStatusListener listener =
		            new ControllerStatusListener(store, new ObjectMapper(), statusRequests, swarmSignals, HiveJournal.noop());

        assertThatCode(() -> listener.handle("{}", "  "))
            .doesNotThrowAnyException();
        verifyNoInteractions(store, statusRequests, swarmSignals);
	    }

    @Test
		    void handleRejectsNullRoutingKey() {
		        ControllerStatusListener listener =
		            new ControllerStatusListener(store, new ObjectMapper(), statusRequests, swarmSignals, HiveJournal.noop());

        assertThatCode(() -> listener.handle("{}", null))
            .doesNotThrowAnyException();
        verifyNoInteractions(store, statusRequests, swarmSignals);
	    }

    @Test
		    void handleRejectsBlankPayload() {
		        ControllerStatusListener listener =
		            new ControllerStatusListener(store, new ObjectMapper(), statusRequests, swarmSignals, HiveJournal.noop());

        assertThatCode(() -> listener.handle(" ", "event.metric.status-delta.sw1.swarm-controller.inst1"))
            .doesNotThrowAnyException();
        verifyNoInteractions(store, statusRequests, swarmSignals);
	    }
}
