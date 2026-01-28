package io.pockethive.orchestrator.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.orchestrator.domain.SwarmLifecycleStatus;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;

class ControllerStatusListenerRebuildTest {

    @Test
    void rebuildsSwarmFromControllerStatusAndRequestsFullSnapshot() {
        SwarmStore store = new SwarmStore();
        ObjectMapper mapper = new ObjectMapper();
        ControlPlaneStatusRequestPublisher requests = Mockito.mock(ControlPlaneStatusRequestPublisher.class);
        SwarmSignalListener swarmSignals = Mockito.mock(SwarmSignalListener.class);
        ControllerStatusListener listener =
            new ControllerStatusListener(store, mapper, requests, swarmSignals);

        String json = """
            {
              "timestamp": "2024-01-01T00:00:00Z",
              "version": "1",
              "kind": "metric",
              "type": "status-full",
              "origin": "controller-1",
	              "scope": {"swarmId":"sw1","role":"swarm-controller","instance":"controller-1"},
	              "correlationId": "c1",
	              "idempotencyKey": "i1",
	              "runtime": {
	                "templateId": "tpl-1",
	                "runId": "run-1",
	                "containerId": "container-1",
	                "image": "swarm-controller:latest",
	                "stackName": "ph-sw1"
	              },
	              "data": {
	                "enabled": false,
	                "ioState": {"filesystem": {"input":"unknown","output":"unknown"}},
	                "config": {},
	                "startedAt": "2024-01-01T00:00:00Z",
	                "io": {},
	                "context": {"swarmStatus": "STOPPED", "journal": {"runId":"run-1"}}
	              }
            }
            """;

        listener.handle(json, "event.metric.status-full.sw1.swarm-controller.controller-1");

        assertThat(store.find("sw1")).isPresent();
        assertThat(store.find("sw1").orElseThrow().getStatus()).isEqualTo(SwarmLifecycleStatus.STOPPED);
        verify(requests).requestStatusForSwarm(eq("sw1"), anyString(), anyString());
    }

    @Test
    void rebuildRequestsSnapshotForEachDeltaUntilFullArrives() {
        SwarmStore store = new SwarmStore();
        ObjectMapper mapper = new ObjectMapper();
        ControlPlaneStatusRequestPublisher requests = Mockito.mock(ControlPlaneStatusRequestPublisher.class);
        SwarmSignalListener swarmSignals = Mockito.mock(SwarmSignalListener.class);
        ControllerStatusListener listener =
            new ControllerStatusListener(store, mapper, requests, swarmSignals);

        String json = """
            {
              "timestamp": "2024-01-01T00:00:00Z",
              "version": "1",
              "kind": "metric",
              "type": "status-delta",
              "origin": "controller-1",
	              "scope": {"swarmId":"sw1","role":"swarm-controller","instance":"controller-1"},
	              "correlationId": "c1",
	              "idempotencyKey": "i1",
	              "runtime": { "templateId": "tpl-1", "runId": "run-1" },
	              "data": {"enabled": true, "ioState": {}, "context": {"swarmStatus": "RUNNING"}}
	            }
            """;

        listener.handle(json, "event.metric.status-delta.sw1.swarm-controller.controller-1");
        listener.handle(json, "event.metric.status-delta.sw1.swarm-controller.controller-1");

        verify(requests, Mockito.times(2)).requestStatusForSwarm(eq("sw1"), anyString(), anyString());
    }
}
