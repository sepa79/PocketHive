package io.pockethive.controlplane.topology;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.Topology;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ControlPlaneTopologyDescriptorsTest {

    private static final String INSTANCE = "inst";

    @Test
    void processorDescriptorMatchesRabbitConfig() {
        ProcessorControlPlaneTopologyDescriptor descriptor = new ProcessorControlPlaneTopologyDescriptor();

        ControlQueueDescriptor queue = requireQueue(descriptor);
        assertThat(queue.name())
            .isEqualTo(Topology.CONTROL_QUEUE + "." + Topology.SWARM_ID + ".processor." + INSTANCE);
        assertThat(queue.signalBindings())
            .containsExactlyInAnyOrderElementsOf(expectedWorkerSignals("processor", INSTANCE));
        assertThat(queue.eventBindings()).isEmpty();
        assertThat(descriptor.additionalQueues(INSTANCE))
            .containsExactly(new QueueDescriptor(Topology.MOD_QUEUE, Set.of(Topology.MOD_QUEUE)));

        ControlPlaneRouteCatalog routes = descriptor.routes();
        assertThat(routes.configSignals())
            .containsExactlyInAnyOrderElementsOf(expectedWorkerConfigSignals("processor", ControlPlaneRouteCatalog.INSTANCE_TOKEN));
        assertThat(routes.statusSignals())
            .containsExactlyInAnyOrderElementsOf(expectedWorkerStatusSignals("processor", ControlPlaneRouteCatalog.INSTANCE_TOKEN));
    }

    @Test
    void generatorDescriptorMatchesRabbitConfig() {
        GeneratorControlPlaneTopologyDescriptor descriptor = new GeneratorControlPlaneTopologyDescriptor();

        ControlQueueDescriptor queue = requireQueue(descriptor);
        assertThat(queue.name())
            .isEqualTo(Topology.CONTROL_QUEUE + "." + Topology.SWARM_ID + ".generator." + INSTANCE);
        assertThat(queue.signalBindings())
            .containsExactlyInAnyOrderElementsOf(expectedWorkerSignals("generator", INSTANCE));
        assertThat(queue.eventBindings()).isEmpty();
        assertThat(descriptor.additionalQueues(INSTANCE)).isEmpty();
    }

    @Test
    void triggerDescriptorMatchesRabbitConfig() {
        TriggerControlPlaneTopologyDescriptor descriptor = new TriggerControlPlaneTopologyDescriptor();

        ControlQueueDescriptor queue = requireQueue(descriptor);
        assertThat(queue.name())
            .isEqualTo(Topology.CONTROL_QUEUE + "." + Topology.SWARM_ID + ".trigger." + INSTANCE);
        assertThat(queue.signalBindings())
            .containsExactlyInAnyOrderElementsOf(expectedWorkerSignals("trigger", INSTANCE));
        assertThat(queue.eventBindings()).isEmpty();
        assertThat(descriptor.additionalQueues(INSTANCE)).isEmpty();
    }

    @Test
    void moderatorDescriptorMatchesRabbitConfig() {
        ModeratorControlPlaneTopologyDescriptor descriptor = new ModeratorControlPlaneTopologyDescriptor();

        ControlQueueDescriptor queue = requireQueue(descriptor);
        assertThat(queue.name())
            .isEqualTo(Topology.CONTROL_QUEUE + "." + Topology.SWARM_ID + ".moderator." + INSTANCE);
        assertThat(queue.signalBindings())
            .containsExactlyInAnyOrderElementsOf(expectedWorkerSignals("moderator", INSTANCE));
        assertThat(queue.eventBindings()).isEmpty();
        assertThat(descriptor.additionalQueues(INSTANCE))
            .containsExactly(new QueueDescriptor(Topology.GEN_QUEUE, Set.of(Topology.GEN_QUEUE)));
    }

    @Test
    void postProcessorDescriptorMatchesRabbitConfig() {
        PostProcessorControlPlaneTopologyDescriptor descriptor = new PostProcessorControlPlaneTopologyDescriptor();

        ControlQueueDescriptor queue = requireQueue(descriptor);
        assertThat(queue.name())
            .isEqualTo(Topology.CONTROL_QUEUE + "." + Topology.SWARM_ID + ".postprocessor." + INSTANCE);
        assertThat(queue.signalBindings())
            .containsExactlyInAnyOrderElementsOf(expectedWorkerSignals("postprocessor", INSTANCE));
        assertThat(queue.eventBindings()).isEmpty();
        assertThat(descriptor.additionalQueues(INSTANCE))
            .containsExactly(new QueueDescriptor(Topology.FINAL_QUEUE, Set.of(Topology.FINAL_QUEUE)));
    }

    @Test
    void swarmControllerDescriptorMatchesRabbitConfig() {
        SwarmControllerControlPlaneTopologyDescriptor descriptor = new SwarmControllerControlPlaneTopologyDescriptor();

        ControlQueueDescriptor queue = requireQueue(descriptor);
        assertThat(queue.name())
            .isEqualTo(Topology.CONTROL_QUEUE + ".swarm-controller." + INSTANCE);
        assertThat(queue.signalBindings())
            .containsExactlyInAnyOrderElementsOf(expectedSwarmControllerSignals(INSTANCE));
        assertThat(queue.eventBindings())
            .containsExactlyInAnyOrder("ev.status-full." + Topology.SWARM_ID + ".#", "ev.status-delta." + Topology.SWARM_ID + ".#");

        ControlPlaneRouteCatalog routes = descriptor.routes();
        assertThat(routes.configSignals())
            .containsExactlyInAnyOrderElementsOf(expectedSwarmControllerConfigSignals(ControlPlaneRouteCatalog.INSTANCE_TOKEN));
        assertThat(routes.statusSignals())
            .containsExactlyInAnyOrderElementsOf(expectedSwarmControllerStatusSignals(ControlPlaneRouteCatalog.INSTANCE_TOKEN));
        assertThat(routes.lifecycleSignals())
            .containsExactlyInAnyOrder(
                ControlPlaneRouting.signal("swarm-start", Topology.SWARM_ID, "swarm-controller", "ALL"),
                ControlPlaneRouting.signal("swarm-template", Topology.SWARM_ID, "swarm-controller", "ALL"),
                ControlPlaneRouting.signal("swarm-stop", Topology.SWARM_ID, "swarm-controller", "ALL"),
                ControlPlaneRouting.signal("swarm-remove", Topology.SWARM_ID, "swarm-controller", "ALL"));
        assertThat(routes.statusEvents())
            .containsExactlyInAnyOrder("ev.status-full." + Topology.SWARM_ID + ".#", "ev.status-delta." + Topology.SWARM_ID + ".#");
    }

    @Test
    void orchestratorDescriptorMatchesRabbitConfig() {
        OrchestratorControlPlaneTopologyDescriptor descriptor = new OrchestratorControlPlaneTopologyDescriptor();

        ControlQueueDescriptor queue = requireQueue(descriptor);
        assertThat(queue.name())
            .isEqualTo(Topology.CONTROL_QUEUE + ".orchestrator." + INSTANCE);
        assertThat(queue.signalBindings()).isEmpty();
        assertThat(queue.eventBindings())
            .containsExactlyInAnyOrder("ev.ready.#", "ev.error.#");

        Collection<QueueDescriptor> additional = descriptor.additionalQueues(INSTANCE);
        assertThat(additional)
            .containsExactly(new QueueDescriptor(
                Topology.CONTROL_QUEUE + ".orchestrator-status." + INSTANCE,
                Set.of("ev.status-full.swarm-controller.*", "ev.status-delta.swarm-controller.*")));

        ControlPlaneRouteCatalog routes = descriptor.routes();
        assertThat(routes.lifecycleEvents())
            .containsExactlyInAnyOrder("ev.ready.#", "ev.error.#");
        assertThat(routes.statusEvents())
            .containsExactlyInAnyOrder("ev.status-full.swarm-controller.*", "ev.status-delta.swarm-controller.*");
    }

    @Test
    void scenarioManagerDescriptorDeclaresNoTopology() {
        ScenarioManagerTopologyDescriptor descriptor = new ScenarioManagerTopologyDescriptor();

        assertThat(descriptor.controlQueue(INSTANCE)).isEmpty();
        assertThat(descriptor.additionalQueues(INSTANCE)).isEmpty();
        assertThat(descriptor.routes()).isEqualTo(ControlPlaneRouteCatalog.empty());
    }

    private static ControlQueueDescriptor requireQueue(ControlPlaneTopologyDescriptor descriptor) {
        return descriptor.controlQueue(INSTANCE).orElseThrow();
    }

    private static Set<String> expectedWorkerSignals(String role, String instanceSegment) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(expectedWorkerConfigSignals(role, instanceSegment));
        merged.addAll(expectedWorkerStatusSignals(role, instanceSegment));
        return Set.copyOf(merged);
    }

    private static Set<String> expectedWorkerConfigSignals(String role, String instanceSegment) {
        return Set.of(
            ControlPlaneRouting.signal("config-update", "ALL", role, "ALL"),
            ControlPlaneRouting.signal("config-update", Topology.SWARM_ID, role, "ALL"),
            ControlPlaneRouting.signal("config-update", Topology.SWARM_ID, role, instanceSegment),
            ControlPlaneRouting.signal("config-update", Topology.SWARM_ID, "ALL", "ALL")
        );
    }

    private static Set<String> expectedWorkerStatusSignals(String role, String instanceSegment) {
        return Set.of(
            ControlPlaneRouting.signal("status-request", "ALL", role, "ALL"),
            ControlPlaneRouting.signal("status-request", Topology.SWARM_ID, role, "ALL"),
            ControlPlaneRouting.signal("status-request", Topology.SWARM_ID, role, instanceSegment)
        );
    }

    private static Set<String> expectedSwarmControllerSignals(String instanceSegment) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(expectedSwarmControllerConfigSignals(instanceSegment));
        merged.addAll(expectedSwarmControllerStatusSignals(instanceSegment));
        merged.add(ControlPlaneRouting.signal("swarm-start", Topology.SWARM_ID, "swarm-controller", "ALL"));
        merged.add(ControlPlaneRouting.signal("swarm-template", Topology.SWARM_ID, "swarm-controller", "ALL"));
        merged.add(ControlPlaneRouting.signal("swarm-stop", Topology.SWARM_ID, "swarm-controller", "ALL"));
        merged.add(ControlPlaneRouting.signal("swarm-remove", Topology.SWARM_ID, "swarm-controller", "ALL"));
        return Set.copyOf(merged);
    }

    private static Set<String> expectedSwarmControllerConfigSignals(String instanceSegment) {
        return Set.of(
            ControlPlaneRouting.signal("config-update", "ALL", "swarm-controller", "ALL"),
            ControlPlaneRouting.signal("config-update", Topology.SWARM_ID, "swarm-controller", "ALL"),
            ControlPlaneRouting.signal("config-update", Topology.SWARM_ID, "swarm-controller", instanceSegment),
            ControlPlaneRouting.signal("config-update", Topology.SWARM_ID, "ALL", "ALL")
        );
    }

    private static Set<String> expectedSwarmControllerStatusSignals(String instanceSegment) {
        return Set.of(
            ControlPlaneRouting.signal("status-request", "ALL", "swarm-controller", "ALL"),
            ControlPlaneRouting.signal("status-request", Topology.SWARM_ID, "swarm-controller", "ALL"),
            ControlPlaneRouting.signal("status-request", Topology.SWARM_ID, "swarm-controller", instanceSegment),
            ControlPlaneRouting.signal("status-request", Topology.SWARM_ID, "ALL", "ALL")
        );
    }
}
