package io.pockethive.orchestrator.runtime;

import io.pockethive.rabbit.api.RabbitResources;
import io.pockethive.rabbit.api.RabbitQueueObservation;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AmqpRabbitTopologyAdapterTest {
    @Test void projectsObservedCountsAndPreservesFailure() {
        var resources = mock(RabbitResources.class);
        var adapter = new AmqpRabbitTopologyAdapter(resources,
            new io.pockethive.rabbit.work.RabbitWorkResources(resources, connections().work()), connections().control());
        when(resources.queue("jobs")).thenReturn(Optional.of(new RabbitQueueObservation(8, 2, OptionalLong.empty())));
        assertThat(adapter.queue(io.pockethive.swarm.model.lifecycle.ResourcePlane.WORK, "jobs")).contains(new RuntimeCleanupPorts.RabbitQueueResource("jobs", 8, 2));
        when(resources.queue("offline")).thenThrow(new IllegalStateException("broker unavailable"));
        assertThatThrownBy(() -> adapter.queue(io.pockethive.swarm.model.lifecycle.ResourcePlane.WORK, "offline")).hasMessage("broker unavailable");
    }
    @Test void routesEqualNamesOnlyToTheExplicitPlane() {
        var control = mock(RabbitResources.class);
        var work = mock(RabbitResources.class);
        var adapter = new AmqpRabbitTopologyAdapter(control,
            new io.pockethive.rabbit.work.RabbitWorkResources(work, connections().work()), connections().control());
        adapter.deleteQueue(io.pockethive.swarm.model.lifecycle.ResourcePlane.WORK, "jobs");
        verify(work).deleteQueue("jobs");
        verifyNoInteractions(control);
        adapter.deleteExchange(io.pockethive.swarm.model.lifecycle.ResourcePlane.CONTROL, "events");
        verify(control).deleteExchange("events");
        verifyNoMoreInteractions(work, control);
        assertThatThrownBy(() -> adapter.deleteQueue(io.pockethive.swarm.model.lifecycle.ResourcePlane.NONE, "jobs"))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoMoreInteractions(work, control);
    }

    private static io.pockethive.rabbit.api.RabbitConnections connections() {
        return new io.pockethive.rabbit.api.RabbitConnections(
            new io.pockethive.rabbit.api.RabbitConnectionSettings("control", 5672, "user", "secret", "/control"),
            new io.pockethive.rabbit.api.RabbitConnectionSettings("work", 5673, "user", "secret", "/work"));
    }

}
