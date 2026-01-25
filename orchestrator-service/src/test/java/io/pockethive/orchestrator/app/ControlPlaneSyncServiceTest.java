package io.pockethive.orchestrator.app;

import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ControlPlaneSyncServiceTest {

    @Test
    void refreshRequestsAllControllersWhenNoSwarmsKnown() {
        SwarmStore store = Mockito.mock(SwarmStore.class);
        SwarmSignalListener orchestratorSignals = Mockito.mock(SwarmSignalListener.class);
        ControlPlaneStatusRequestPublisher publisher = Mockito.mock(ControlPlaneStatusRequestPublisher.class);
        Clock clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

        when(store.all()).thenReturn(List.of());

        ControlPlaneSyncService service = new ControlPlaneSyncService(store, orchestratorSignals, publisher, clock);
        ControlPlaneSyncResponse response = service.refresh();

        verify(orchestratorSignals).requestStatusFull();
        verify(publisher).requestStatusForAllControllers(anyString(), anyString());
        verify(publisher, never()).requestStatusForSwarm(anyString(), anyString(), anyString());
        assertThat(response.throttled()).isFalse();
        assertThat(response.signalsPublished()).isEqualTo(1);
    }

    @Test
    void refreshRequestsKnownSwarmsWhenRegistryNotEmpty() {
        SwarmStore store = Mockito.mock(SwarmStore.class);
        SwarmSignalListener orchestratorSignals = Mockito.mock(SwarmSignalListener.class);
        ControlPlaneStatusRequestPublisher publisher = Mockito.mock(ControlPlaneStatusRequestPublisher.class);
        Clock clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

        when(store.all()).thenReturn(List.of(
            new Swarm("sw1", "controller-1", "cid-1", "run-1"),
            new Swarm("sw2", "controller-2", "cid-2", "run-2")
        ));

        ControlPlaneSyncService service = new ControlPlaneSyncService(store, orchestratorSignals, publisher, clock);
        ControlPlaneSyncResponse response = service.refresh();

        verify(orchestratorSignals).requestStatusFull();
        verify(publisher).requestStatusForSwarm(Mockito.eq("sw1"), anyString(), anyString());
        verify(publisher).requestStatusForSwarm(Mockito.eq("sw2"), anyString(), anyString());
        verify(publisher, never()).requestStatusForAllControllers(anyString(), anyString());
        assertThat(response.throttled()).isFalse();
        assertThat(response.signalsPublished()).isEqualTo(2);
    }

    @Test
    void resetClearsRegistryBeforeBroadcast() {
        SwarmStore store = Mockito.mock(SwarmStore.class);
        SwarmSignalListener orchestratorSignals = Mockito.mock(SwarmSignalListener.class);
        ControlPlaneStatusRequestPublisher publisher = Mockito.mock(ControlPlaneStatusRequestPublisher.class);
        Clock clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

        when(store.all()).thenReturn(List.of());

        ControlPlaneSyncService service = new ControlPlaneSyncService(store, orchestratorSignals, publisher, clock);
        service.reset();

        verify(store).clear();
        verify(orchestratorSignals).requestStatusFull();
        verify(publisher).requestStatusForAllControllers(anyString(), anyString());
    }
}
