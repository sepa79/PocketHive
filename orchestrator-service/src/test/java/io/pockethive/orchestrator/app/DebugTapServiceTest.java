package io.pockethive.orchestrator.app;

import io.pockethive.swarm.model.NetworkMode;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.pockethive.orchestrator.app.DebugTapController.DebugTapRequest;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.Work;
import io.pockethive.rabbit.api.RabbitResourceNames;
import io.pockethive.topology.work.WorkResourceNamesPort;
import io.pockethive.topology.work.WorkTopologySettings;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import io.pockethive.rabbit.api.RabbitResources;
import io.pockethive.rabbit.api.RabbitBindingSpec;
import io.pockethive.rabbit.api.RabbitReceiver;
import org.springframework.web.server.ResponseStatusException;

class DebugTapServiceTest {

    @Test
    void cleanupExpiredRemovesTapAndDeletesQueue() {
        SwarmStore store = new SwarmStore();
        var declaredBindings = new CopyOnWriteArrayList<RabbitBindingSpec>();
        var deletedQueues = new CopyOnWriteArrayList<String>();
        RabbitResources amqp = recordingRabbitResources(declaredBindings, deletedQueues);
        RabbitReceiver rabbit = mock(RabbitReceiver.class);

        Swarm swarm = new Swarm("sw1", "inst-1", "c1", "run-1", NetworkMode.DIRECT);
        swarm.attachTemplate(new io.pockethive.orchestrator.domain.SwarmTemplateMetadata(
            "tpl-1",
            "swarm-controller:latest",
            List.of(new Bee("processor", "processor:latest", Work.ofDefaults("in", "final"), Map.of()))
        ));
        store.register(swarm);

        DebugTapService service = new DebugTapService(store, amqp, rabbit, new RabbitResourceNames());
        var created = service.create(new DebugTapRequest("sw1", "processor", "OUT", null, 1, 1));
        String tapId = created.tapId();
        String queue = created.queue();
        assertThat(created.exchange()).isEqualTo("ph.sw1.hive");
        assertThat(created.routingKey()).isEqualTo("ph.sw1.final");

        assertThat(declaredBindings).hasSize(1);
        assertThat(declaredBindings.getFirst().exchange()).isEqualTo("ph.sw1.hive");
        assertThat(declaredBindings.getFirst().routingKey()).isEqualTo("ph.sw1.final");

        service.cleanupExpired(Instant.now().plusSeconds(5));

        assertThat(deletedQueues).contains(queue);
        assertThatThrownBy(() -> service.read(tapId, 1))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("debug tap not found");
    }

    @Test
    void bindsBothDirectionsToResolvedSourceResources() {
        var store = new SwarmStore();
        var swarm = new Swarm("sw1", "inst-1", "c1", "run-1", NetworkMode.DIRECT);
        swarm.attachTemplate(new io.pockethive.orchestrator.domain.SwarmTemplateMetadata(
            "tpl-1", "controller", List.of(
                new Bee("processor", "image", Work.ofDefaults("input", "output"), Map.of()))));
        store.register(swarm);
        var names = mock(WorkResourceNamesPort.class);
        when(names.forSwarm("sw1")).thenReturn(new WorkTopologySettings("selected", "configured.exchange"));
        when(names.address("configured.exchange", "selected", "input"))
            .thenReturn(new io.pockethive.topology.work.WorkAddress("selected.exchange", "queue.input", "selected.input"));
        when(names.address("configured.exchange", "selected", "output"))
            .thenReturn(new io.pockethive.topology.work.WorkAddress("selected.exchange", "queue.output", "selected.output"));
        var bindings = new CopyOnWriteArrayList<RabbitBindingSpec>();
        var service = new DebugTapService(store, recordingRabbitResources(bindings, new CopyOnWriteArrayList<>()),
            mock(RabbitReceiver.class), names);

        var input = service.create(new DebugTapRequest("sw1", "processor", "IN", null, 1, 60));
        var output = service.create(new DebugTapRequest("sw1", "processor", "OUT", null, 1, 60));

        assertThat(input.exchange()).isEqualTo("selected.exchange");
        assertThat(input.routingKey()).isEqualTo("selected.input");
        assertThat(output.exchange()).isEqualTo("selected.exchange");
        assertThat(output.routingKey()).isEqualTo("selected.output");
        assertThat(bindings).extracting(RabbitBindingSpec::exchange)
            .containsExactly("selected.exchange", "selected.exchange");
        assertThat(bindings).extracting(RabbitBindingSpec::routingKey)
            .containsExactly("selected.input", "selected.output");
        assertThat(bindings).extracting(RabbitBindingSpec::queue)
            .containsExactly(input.queue(), output.queue());
    }

    private static RabbitResources recordingRabbitResources(List<RabbitBindingSpec> bindings, List<String> deletedQueues) {
        Objects.requireNonNull(bindings, "bindings");
        Objects.requireNonNull(deletedQueues, "deletedQueues");

        var resources = org.mockito.Mockito.mock(RabbitResources.class);
        org.mockito.Mockito.doAnswer(call -> {
            bindings.add(call.getArgument(0));
            return null;
        }).when(resources).bind(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doAnswer(call -> {
            deletedQueues.add(call.getArgument(0));
            return null;
        }).when(resources).deleteQueue(org.mockito.ArgumentMatchers.anyString());
        return resources;
    }
}
