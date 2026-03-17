package io.pockethive.orchestrator.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.messaging.SignalMessage;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.spring.ControlPlaneProperties;
import io.pockethive.orchestrator.domain.HiveJournal;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.swarm.model.NetworkBinding;
import io.pockethive.swarm.model.NetworkMode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SwarmNetworkBindingServiceTest {

    @Mock
    private NetworkProxyClient networkProxyClient;

    @Mock
    private HiveJournal hiveJournal;

    @Mock
    private ControlPlanePublisher publisher;

    private final ObjectMapper mapper = new JacksonConfiguration().objectMapper();

    @Test
    void clearBindingAppendsRequestedByToJournal() throws Exception {
        SwarmNetworkBindingService service = service();
        when(networkProxyClient.clearSwarm(eq("sw1"), any(), eq("corr-1"), eq("idem-1")))
            .thenReturn(new NetworkBinding(
                "sw1",
                "sut-a",
                NetworkMode.DIRECT,
                null,
                NetworkMode.DIRECT,
                "orchestrator",
                Instant.parse("2026-03-16T16:00:00Z"),
                List.of()));

        service.clearBinding("sw1", "sut-a", "corr-1", "idem-1", "orchestrator", "swarm-remove", "orchestrator");

        ArgumentCaptor<HiveJournal.HiveJournalEntry> entryCaptor =
            ArgumentCaptor.forClass(HiveJournal.HiveJournalEntry.class);
        verify(hiveJournal).append(entryCaptor.capture());
        HiveJournal.HiveJournalEntry entry = entryCaptor.getValue();
        assertThat(entry.type()).isEqualTo("network-binding-clear");
        assertThat(entry.data()).containsEntry("requestedBy", "orchestrator");
        assertThat(entry.data()).containsEntry("effectiveMode", NetworkMode.DIRECT.name());
    }

    @Test
    void publishControllerNetworkContextPublishesConfigUpdateSignal() throws Exception {
        SwarmNetworkBindingService service = service();
        Swarm swarm = new Swarm("sw1", "controller-1", "cid-1", "run-1");
        swarm.setSutId("sut-a");

        service.publishControllerNetworkContext(
            swarm,
            "sut-a",
            NetworkMode.PROXIED,
            "latency-250ms",
            "corr-1",
            "idem-1");

        ArgumentCaptor<SignalMessage> messageCaptor = ArgumentCaptor.forClass(SignalMessage.class);
        verify(publisher).publishSignal(messageCaptor.capture());
        SignalMessage message = messageCaptor.getValue();
        assertThat(message.routingKey())
            .isEqualTo(ControlPlaneRouting.signal(
                ControlPlaneSignals.CONFIG_UPDATE,
                "sw1",
                "swarm-controller",
                "controller-1"));
        ControlSignal signal = mapper.readValue(message.payload().toString(), ControlSignal.class);
        assertThat(signal.type()).isEqualTo(ControlPlaneSignals.CONFIG_UPDATE);
        assertThat(signal.scope().swarmId()).isEqualTo("sw1");
        assertThat(signal.scope().role()).isEqualTo("swarm-controller");
        assertThat(signal.scope().instance()).isEqualTo("controller-1");
        assertThat(signal.correlationId()).isEqualTo("corr-1");
        assertThat(signal.idempotencyKey()).isEqualTo("idem-1");
        assertThat(signal.data()).containsEntry("sutId", "sut-a");
        assertThat(signal.data()).containsEntry("networkMode", NetworkMode.PROXIED.name());
        assertThat(signal.data()).containsEntry("networkProfileId", "latency-250ms");
    }

    private SwarmNetworkBindingService service() {
        return new SwarmNetworkBindingService(
            networkProxyClient,
            hiveJournal,
            publisher,
            controlPlaneProperties());
    }

    private static ControlPlaneProperties controlPlaneProperties() {
        ControlPlaneProperties properties = new ControlPlaneProperties();
        properties.setExchange("ph.control");
        properties.setControlQueuePrefix("ph.control.manager");
        properties.setSwarmId("default");
        properties.setInstanceId("orch-instance");
        properties.getManager().setRole("orchestrator");
        return properties;
    }
}
