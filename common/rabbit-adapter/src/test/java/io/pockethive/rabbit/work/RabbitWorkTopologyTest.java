package io.pockethive.rabbit.work;

import io.pockethive.rabbit.api.*;
import io.pockethive.topology.work.*;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RabbitWorkTopologyTest {
    private final RabbitResourceNames names = new RabbitResourceNames();
    private final RabbitResources broker = mock(RabbitResources.class);
    private final RabbitWorkResources resources = new RabbitWorkResources(broker,
        new RabbitConnectionSettings("work", 5672, "user", "secret", "/work"));

    @Test
    void rejectsMissingSettingsEvenWhenThereAreNoChannels() {
        for (var settings : new RabbitWorkTopologySettings[]{
            new RabbitWorkTopologySettings(" ", "hive"), new RabbitWorkTopologySettings(null, "hive"),
            new RabbitWorkTopologySettings("prefix", " "), new RabbitWorkTopologySettings("prefix", null)}) {
            var resolver = new RabbitWorkTopologyResolver(names, swarm -> settings);
            assertThatThrownBy(() -> resolver.resolve("swarm", Set.of())).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void normalizedSettingsAreSharedByControllerAndChannelProjections() {
        var topology = new RabbitWorkTopologyResolver(names,
            swarm -> new RabbitWorkTopologySettings(" prefix ", " hive ")).resolve("swarm", Set.of(" jobs "));
        assertThat(topology.controllerEnvironment()).containsEntry(
            "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_TRAFFIC_QUEUE_PREFIX", "prefix");
        var environment = new org.springframework.core.env.StandardEnvironment();
        environment.getPropertySources().addFirst(new org.springframework.core.env.SystemEnvironmentPropertySource(
            "test", Map.copyOf(topology.controllerEnvironment())));
        assertThat(RabbitControllerTopologyEnvironment.decode(environment::getProperty))
            .isEqualTo(new RabbitWorkTopologySettings("prefix", "hive"));
        assertThat(topology.channel(" jobs ").inputAddress()).isEqualTo("prefix.jobs");
        assertThat(topology.channel(" jobs ").inputEnvironment()).containsEntry(
            RabbitWorkSettingsBootstrap.INPUT_QUEUE_ENV, "prefix.jobs");
    }

    @Test
    void rebindsExistingQueuesAndHealsMissingOnesWithoutChangingDeclarationPolicy() {
        var topology = new RabbitWorkTopologyResolver(names, names::forSwarm).resolve("swarm", Set.of("jobs"));
        var channel = topology.channel("jobs");
        when(broker.queue(channel.inputAddress())).thenReturn(Optional.of(new RabbitQueueObservation(4, 1, OptionalLong.empty())));
        resources.ensure(topology);
        resources.ensure(topology);
        verify(broker, times(1)).declareQueue(new RabbitQueueSpec(channel.inputAddress(), true, false, false, Map.of()));
        when(broker.queue(channel.inputAddress())).thenReturn(Optional.empty());
        resources.ensure(topology);
        verify(broker, times(2)).declareQueue(new RabbitQueueSpec(channel.inputAddress(), true, false, false, Map.of()));
        verify(broker, times(3)).bind(new RabbitBindingSpec(channel.inputAddress(), "ph.swarm.hive", channel.outputAddress(), Map.of()));
    }

    @Test
    void failedBindingIsNotReportedAsAnAppliedChannel() {
        var topology = new RabbitWorkTopologyResolver(names, names::forSwarm).resolve("swarm", Set.of("jobs"));
        doThrow(new IllegalStateException("bind failed")).when(broker).bind(any());
        assertThatThrownBy(() -> resources.ensure(topology)).hasMessage("bind failed");
        assertThat(resources.appliedResources()).isEmpty();
        assertThat(topology.retainChannels(resources.appliedResources()).channels()).isEmpty();
    }

    @Test
    void propagatesFailedObservationsAndDeletionWithoutReportingAbsence() {
        var queue = RabbitWorkResourceKind.QUEUE.identity("jobs");
        when(broker.queue("jobs")).thenThrow(new IllegalStateException("broker offline"));
        assertThatThrownBy(() -> resources.observe(queue)).hasMessage("broker offline");
        doThrow(new IllegalStateException("delete failed")).when(broker).deleteQueue("jobs");
        assertThatThrownBy(() -> resources.remove(queue)).hasMessage("delete failed");
    }
}
