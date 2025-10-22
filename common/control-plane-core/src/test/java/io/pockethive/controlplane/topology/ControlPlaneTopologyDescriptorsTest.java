package io.pockethive.controlplane.topology;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.Topology;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.payload.JsonFixtureAssertions;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ControlPlaneTopologyDescriptorsTest {

    private static final String INSTANCE = "inst";
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final ControlPlaneTopologySettings SETTINGS =
        new ControlPlaneTopologySettings(Topology.SWARM_ID, Topology.CONTROL_QUEUE, Map.of());

    @Test
    void processorDescriptorMatchesRabbitConfig() {
        ProcessorControlPlaneTopologyDescriptor descriptor = new ProcessorControlPlaneTopologyDescriptor(SETTINGS);

        ControlQueueDescriptor queue = requireQueue(descriptor);
        assertThat(queue.name())
            .isEqualTo(Topology.CONTROL_QUEUE + "." + Topology.SWARM_ID + ".processor." + INSTANCE);
        assertThat(queue.signalBindings())
            .containsExactlyInAnyOrderElementsOf(expectedWorkerSignals("processor", INSTANCE));
        assertThat(queue.eventBindings()).isEmpty();
        assertThat(descriptor.additionalQueues(INSTANCE)).isEmpty();

        ControlPlaneRouteCatalog routes = descriptor.routes();
        assertThat(routes.configSignals())
            .containsExactlyInAnyOrderElementsOf(expectedWorkerConfigSignals("processor", ControlPlaneRouteCatalog.INSTANCE_TOKEN));
        assertThat(routes.statusSignals())
            .containsExactlyInAnyOrderElementsOf(expectedWorkerStatusSignals("processor", ControlPlaneRouteCatalog.INSTANCE_TOKEN));
    }

    @Test
    void descriptorDslMatchesGoldenFixture() throws IOException {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("processor", describe(new ProcessorControlPlaneTopologyDescriptor(SETTINGS)));
        document.put("generator", describe(new GeneratorControlPlaneTopologyDescriptor(SETTINGS)));
        document.put("trigger", describe(new TriggerControlPlaneTopologyDescriptor(SETTINGS)));
        document.put("moderator", describe(new ModeratorControlPlaneTopologyDescriptor(SETTINGS)));
        document.put("postprocessor", describe(new PostProcessorControlPlaneTopologyDescriptor(SETTINGS)));
        document.put("swarmController", describe(new SwarmControllerControlPlaneTopologyDescriptor(SETTINGS)));
        document.put("orchestrator", describe(new OrchestratorControlPlaneTopologyDescriptor(SETTINGS)));
        document.put("scenarioManager", describe(new ScenarioManagerTopologyDescriptor()));

        String json = MAPPER.writeValueAsString(document);
        JsonFixtureAssertions.assertMatchesFixture(
            "/io/pockethive/controlplane/topology/topology-descriptors.json",
            json);
    }

    @Test
    void generatorDescriptorMatchesRabbitConfig() {
        GeneratorControlPlaneTopologyDescriptor descriptor = new GeneratorControlPlaneTopologyDescriptor(SETTINGS);

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
        TriggerControlPlaneTopologyDescriptor descriptor = new TriggerControlPlaneTopologyDescriptor(SETTINGS);

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
        ModeratorControlPlaneTopologyDescriptor descriptor = new ModeratorControlPlaneTopologyDescriptor(SETTINGS);

        ControlQueueDescriptor queue = requireQueue(descriptor);
        assertThat(queue.name())
            .isEqualTo(Topology.CONTROL_QUEUE + "." + Topology.SWARM_ID + ".moderator." + INSTANCE);
        assertThat(queue.signalBindings())
            .containsExactlyInAnyOrderElementsOf(expectedWorkerSignals("moderator", INSTANCE));
        assertThat(queue.eventBindings()).isEmpty();
        assertThat(descriptor.additionalQueues(INSTANCE)).isEmpty();
    }

    @Test
    void postProcessorDescriptorMatchesRabbitConfig() {
        PostProcessorControlPlaneTopologyDescriptor descriptor = new PostProcessorControlPlaneTopologyDescriptor(SETTINGS);

        ControlQueueDescriptor queue = requireQueue(descriptor);
        assertThat(queue.name())
            .isEqualTo(Topology.CONTROL_QUEUE + "." + Topology.SWARM_ID + ".postprocessor." + INSTANCE);
        assertThat(queue.signalBindings())
            .containsExactlyInAnyOrderElementsOf(expectedWorkerSignals("postprocessor", INSTANCE));
        assertThat(queue.eventBindings()).isEmpty();
        assertThat(descriptor.additionalQueues(INSTANCE)).isEmpty();
    }

    @Test
    void swarmControllerDescriptorMatchesRabbitConfig() {
        SwarmControllerControlPlaneTopologyDescriptor descriptor = new SwarmControllerControlPlaneTopologyDescriptor(SETTINGS);

        ControlQueueDescriptor queue = requireQueue(descriptor);
        assertThat(queue.name())
            .isEqualTo(expectedSwarmControllerQueueName(Topology.CONTROL_QUEUE, Topology.SWARM_ID, INSTANCE));
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
                ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_START, Topology.SWARM_ID, "swarm-controller", "ALL"),
                ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_TEMPLATE, Topology.SWARM_ID, "swarm-controller", "ALL"),
                ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_STOP, Topology.SWARM_ID, "swarm-controller", "ALL"),
                ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_REMOVE, Topology.SWARM_ID, "swarm-controller", "ALL"));
        assertThat(routes.statusEvents())
            .containsExactlyInAnyOrder("ev.status-full." + Topology.SWARM_ID + ".#", "ev.status-delta." + Topology.SWARM_ID + ".#");
    }

    private static String expectedSwarmControllerQueueName(String baseQueue, String swarmId, String instanceSegment) {
        List<String> segments = new ArrayList<>();
        for (String segment : baseQueue.split("\\.")) {
            if (!segment.isBlank()) {
                segments.add(segment);
            }
        }
        if (!segments.contains(swarmId)) {
            segments.add(swarmId);
        }
        segments.add("swarm-controller");
        segments.add(instanceSegment);
        return String.join(".", segments);
    }

    @Test
    void orchestratorDescriptorMatchesRabbitConfig() {
        OrchestratorControlPlaneTopologyDescriptor descriptor = new OrchestratorControlPlaneTopologyDescriptor(SETTINGS);

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

    private Map<String, Object> describe(ControlPlaneTopologyDescriptor descriptor) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("role", descriptor.role());
        node.put("controlQueue", descriptor.controlQueue(INSTANCE).map(this::describe).orElse(null));
        node.put("additionalQueues", describeQueues(descriptor.additionalQueues(INSTANCE)));
        ControlPlaneRouteCatalog routes = descriptor.routes();
        node.put("routes", Map.of(
            "configSignals", sorted(routes.configSignals()),
            "statusSignals", sorted(routes.statusSignals()),
            "lifecycleSignals", sorted(routes.lifecycleSignals()),
            "statusEvents", sorted(routes.statusEvents()),
            "lifecycleEvents", sorted(routes.lifecycleEvents()),
            "otherEvents", sorted(routes.otherEvents())
        ));
        return node;
    }

    private Map<String, Object> describe(ControlQueueDescriptor queue) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("name", queue.name());
        node.put("signalBindings", sorted(queue.signalBindings()));
        node.put("eventBindings", sorted(queue.eventBindings()));
        node.put("allBindings", sorted(queue.allBindings()));
        return node;
    }

    private List<Map<String, Object>> describeQueues(Collection<QueueDescriptor> queues) {
        return queues.stream()
            .sorted(Comparator.comparing(QueueDescriptor::name))
            .map(queue -> Map.of(
                "name", queue.name(),
                "bindings", sorted(queue.bindings())
            ))
            .collect(Collectors.toList());
    }

    private List<String> sorted(Set<String> values) {
        return values.stream().sorted().collect(Collectors.toList());
    }

    private static Set<String> expectedWorkerSignals(String role, String instanceSegment) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(expectedWorkerConfigSignals(role, instanceSegment));
        merged.addAll(expectedWorkerStatusSignals(role, instanceSegment));
        return Set.copyOf(merged);
    }

    private static Set<String> expectedWorkerConfigSignals(String role, String instanceSegment) {
        return Set.of(
            ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, "ALL", role, "ALL"),
            ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, Topology.SWARM_ID, role, "ALL"),
            ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, Topology.SWARM_ID, role, instanceSegment),
            ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, Topology.SWARM_ID, "ALL", "ALL")
        );
    }

    private static Set<String> expectedWorkerStatusSignals(String role, String instanceSegment) {
        return Set.of(
            ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, "ALL", role, "ALL"),
            ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, Topology.SWARM_ID, role, "ALL"),
            ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, Topology.SWARM_ID, role, instanceSegment),
            ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, Topology.SWARM_ID, "ALL", "ALL")
        );
    }

    private static Set<String> expectedSwarmControllerSignals(String instanceSegment) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(expectedSwarmControllerConfigSignals(instanceSegment));
        merged.addAll(expectedSwarmControllerStatusSignals(instanceSegment));
        merged.add(ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_START, Topology.SWARM_ID, "swarm-controller", "ALL"));
        merged.add(ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_TEMPLATE, Topology.SWARM_ID, "swarm-controller", "ALL"));
        merged.add(ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_STOP, Topology.SWARM_ID, "swarm-controller", "ALL"));
        merged.add(ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_REMOVE, Topology.SWARM_ID, "swarm-controller", "ALL"));
        return Set.copyOf(merged);
    }

    private static Set<String> expectedSwarmControllerConfigSignals(String instanceSegment) {
        return Set.of(
            ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, "ALL", "swarm-controller", "ALL"),
            ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, Topology.SWARM_ID, "swarm-controller", "ALL"),
            ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, Topology.SWARM_ID, "swarm-controller", instanceSegment),
            ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, Topology.SWARM_ID, "ALL", "ALL")
        );
    }

    private static Set<String> expectedSwarmControllerStatusSignals(String instanceSegment) {
        return Set.of(
            ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, "ALL", "swarm-controller", "ALL"),
            ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, Topology.SWARM_ID, "swarm-controller", "ALL"),
            ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, Topology.SWARM_ID, "swarm-controller", instanceSegment),
            ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, Topology.SWARM_ID, "ALL", "ALL")
        );
    }
}
