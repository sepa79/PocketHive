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
import io.pockethive.rabbit.work.RabbitWorkDebugTaps;
import io.pockethive.rabbit.work.RabbitWorkTopologyResolver;
import io.pockethive.rabbit.api.RabbitWorkTopologySettings;
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
    void unsupportedSelectedWorkDiagnosticsAreExplicit() {
        var store = new SwarmStore();
        var swarm = new Swarm("sw1", "instance", "controller", "run", NetworkMode.DIRECT);
        swarm.attachTemplate(new io.pockethive.orchestrator.domain.SwarmTemplateMetadata("template", "controller",
            List.of(new Bee("processor", "image", Work.ofDefaults("in", "out"), Map.of()))));
        store.register(swarm);
        io.pockethive.topology.work.WorkDebugTaps unsupported = (swarmId, role, tapId, source, ttl, limit) -> {
            throw new UnsupportedOperationException("Memory Work does not support captures");
        };
        var service = new DebugTapService(store, unsupported, new io.pockethive.worker.sdk.testing.InMemoryWorkTopologyResolver());
        assertThatThrownBy(() -> service.create(new DebugTapRequest("sw1", "processor", "OUT", null, 1, 60)))
            .isInstanceOfSatisfying(ResponseStatusException.class, failure -> {
                assertThat(failure.getStatusCode().value()).isEqualTo(501);
                assertThat(failure.getReason()).isEqualTo("Memory Work does not support captures");
            });
    }

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

        var names = new RabbitResourceNames();
        DebugTapService service = new DebugTapService(store, new RabbitWorkDebugTaps(amqp, rabbit),
            new RabbitWorkTopologyResolver(names, names::forSwarm));
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
        var names = org.mockito.Mockito.spy(new RabbitResourceNames());
        org.mockito.Mockito.doReturn(new RabbitWorkTopologySettings("selected", "selected.exchange")).when(names).forSwarm("sw1");
        when(names.address("selected.exchange", "selected", "input"))
            .thenReturn(new io.pockethive.rabbit.api.RabbitWorkAddress("selected.exchange", "queue.input", "selected.input"));
        when(names.address("selected.exchange", "selected", "output"))
            .thenReturn(new io.pockethive.rabbit.api.RabbitWorkAddress("selected.exchange", "queue.output", "selected.output"));
        var bindings = new CopyOnWriteArrayList<RabbitBindingSpec>();
        var service = new DebugTapService(store, new RabbitWorkDebugTaps(
            recordingRabbitResources(bindings, new CopyOnWriteArrayList<>()), mock(RabbitReceiver.class)),
            new RabbitWorkTopologyResolver(names, names::forSwarm));

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

    @Test
    void explicitCloseReportsAdapterFailureThroughHttp() throws Exception {
        var transport = mock(io.pockethive.topology.work.WorkDebugTap.class);
        var failure = new IllegalStateException("native resource deletion failed");
        org.mockito.Mockito.doThrow(failure).when(transport).close();
        var service = serviceWithTap(transport);
        var tap = service.create(new DebugTapRequest("sw1", "processor", "OUT", "out", 1, 60));
        var controller = new DebugTapController(service,
            mock(io.pockethive.orchestrator.auth.OrchestratorEndpointAuthorization.class));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();

        var result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/debug/taps/" + tap.tapId()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isInternalServerError())
            .andReturn();
        assertThat(result.getResolvedException()).hasCause(failure);
        org.mockito.Mockito.verify(transport, org.mockito.Mockito.times(1)).close();
        assertThatThrownBy(() -> service.describe(tap.tapId())).isInstanceOfSatisfying(
            ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
    }

    @Test
    void explicitCloseSucceedsOnlyAfterAdapterCloseCompletes() {
        var transport = mock(io.pockethive.topology.work.WorkDebugTap.class);
        var service = serviceWithTap(transport);
        var tap = service.create(new DebugTapRequest("sw1", "processor", "OUT", "out", 1, 60));
        assertThat(service.close(tap.tapId()).tapId()).isEqualTo(tap.tapId());
        org.mockito.Mockito.verify(transport, org.mockito.Mockito.times(1)).close();
        assertThatThrownBy(() -> service.describe(tap.tapId())).isInstanceOfSatisfying(
            ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
    }

    private static DebugTapService serviceWithTap(io.pockethive.topology.work.WorkDebugTap transport) {
        var store = new SwarmStore();
        var swarm = new Swarm("sw1", "instance", "controller", "run", NetworkMode.DIRECT);
        swarm.attachTemplate(new io.pockethive.orchestrator.domain.SwarmTemplateMetadata("template", "controller",
            List.of(new Bee("processor", "image", Work.ofDefaults("in", "out"), Map.of()))));
        store.register(swarm);
        return new DebugTapService(store, (swarmId, role, tapId, source, ttl, limit) -> transport,
            new io.pockethive.worker.sdk.testing.InMemoryWorkTopologyResolver());
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
