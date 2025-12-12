package io.pockethive.controlplane.topology;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    private static final String SWARM_ID = "swarm-alpha";
    private static final String CONTROL_QUEUE_PREFIX = "ph.control";
    private static final String INSTANCE = "inst";
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final ControlPlaneTopologySettings SETTINGS =
        new ControlPlaneTopologySettings(SWARM_ID, CONTROL_QUEUE_PREFIX, Map.of());

    @Test
    void processorDescriptorMatchesRabbitConfig() {
        WorkerControlPlaneTopologyDescriptor descriptor = workerDescriptor("processor");

        ControlQueueDescriptor queue = requireQueue(descriptor);
        assertThat(queue.name())
            .isEqualTo(CONTROL_QUEUE_PREFIX + "." + SWARM_ID + ".processor." + INSTANCE);
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
        document.put("processor", describe(workerDescriptor("processor")));
        document.put("generator", describe(workerDescriptor("generator")));
        document.put("trigger", describe(workerDescriptor("trigger")));
        document.put("moderator", describe(workerDescriptor("moderator")));
        document.put("postprocessor", describe(workerDescriptor("postprocessor")));
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
        WorkerControlPlaneTopologyDescriptor descriptor = workerDescriptor("generator");

        ControlQueueDescriptor queue = requireQueue(descriptor);
        assertThat(queue.name())
            .isEqualTo(CONTROL_QUEUE_PREFIX + "." + SWARM_ID + ".generator." + INSTANCE);
        assertThat(queue.signalBindings())
            .containsExactlyInAnyOrderElementsOf(expectedWorkerSignals("generator", INSTANCE));
        assertThat(queue.eventBindings()).isEmpty();
        assertThat(descriptor.additionalQueues(INSTANCE)).isEmpty();
    }

    @Test
    void triggerDescriptorMatchesRabbitConfig() {
        WorkerControlPlaneTopologyDescriptor descriptor = workerDescriptor("trigger");

        ControlQueueDescriptor queue = requireQueue(descriptor);
        assertThat(queue.name())
            .isEqualTo(CONTROL_QUEUE_PREFIX + "." + SWARM_ID + ".trigger." + INSTANCE);
        assertThat(queue.signalBindings())
            .containsExactlyInAnyOrderElementsOf(expectedWorkerSignals("trigger", INSTANCE));
        assertThat(queue.eventBindings()).isEmpty();
        assertThat(descriptor.additionalQueues(INSTANCE)).isEmpty();
    }

    @Test
    void moderatorDescriptorMatchesRabbitConfig() {
        WorkerControlPlaneTopologyDescriptor descriptor = workerDescriptor("moderator");

        ControlQueueDescriptor queue = requireQueue(descriptor);
        assertThat(queue.name())
            .isEqualTo(CONTROL_QUEUE_PREFIX + "." + SWARM_ID + ".moderator." + INSTANCE);
        assertThat(queue.signalBindings())
            .containsExactlyInAnyOrderElementsOf(expectedWorkerSignals("moderator", INSTANCE));
        assertThat(queue.eventBindings()).isEmpty();
        assertThat(descriptor.additionalQueues(INSTANCE)).isEmpty();
    }

    @Test
    void postProcessorDescriptorMatchesRabbitConfig() {
        WorkerControlPlaneTopologyDescriptor descriptor = workerDescriptor("postprocessor");

        ControlQueueDescriptor queue = requireQueue(descriptor);
        assertThat(queue.name())
            .isEqualTo(CONTROL_QUEUE_PREFIX + "." + SWARM_ID + ".postprocessor." + INSTANCE);
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
            .isEqualTo(expectedSwarmControllerQueueName(CONTROL_QUEUE_PREFIX, SWARM_ID, INSTANCE));
        assertThat(queue.signalBindings())
            .containsExactlyInAnyOrderElementsOf(expectedSwarmControllerSignals(INSTANCE));
        assertThat(queue.eventBindings())
            .containsExactlyInAnyOrder("event.metric.status-full." + SWARM_ID + ".#", "event.metric.status-delta." + SWARM_ID + ".#");

        ControlPlaneRouteCatalog routes = descriptor.routes();
        assertThat(routes.configSignals())
            .containsExactlyInAnyOrderElementsOf(expectedSwarmControllerConfigSignals(ControlPlaneRouteCatalog.INSTANCE_TOKEN));
        assertThat(routes.statusSignals())
            .containsExactlyInAnyOrderElementsOf(expectedSwarmControllerStatusSignals(ControlPlaneRouteCatalog.INSTANCE_TOKEN));
        assertThat(routes.lifecycleSignals())
            .containsExactlyInAnyOrder(
                ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_START, SWARM_ID, "swarm-controller", ControlPlaneRouteCatalog.INSTANCE_TOKEN),
                ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_PLAN, SWARM_ID, "swarm-controller", ControlPlaneRouteCatalog.INSTANCE_TOKEN),
                ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_TEMPLATE, SWARM_ID, "swarm-controller", ControlPlaneRouteCatalog.INSTANCE_TOKEN),
                ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_STOP, SWARM_ID, "swarm-controller", ControlPlaneRouteCatalog.INSTANCE_TOKEN),
                ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_REMOVE, SWARM_ID, "swarm-controller", ControlPlaneRouteCatalog.INSTANCE_TOKEN));
        assertThat(routes.statusEvents())
            .containsExactlyInAnyOrder("event.metric.status-full." + SWARM_ID + ".#", "event.metric.status-delta." + SWARM_ID + ".#");
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
            .isEqualTo(CONTROL_QUEUE_PREFIX + ".orchestrator." + INSTANCE);
        assertThat(queue.signalBindings()).isEmpty();
        assertThat(queue.eventBindings())
            .containsExactlyInAnyOrder("event.outcome.#");

        Collection<QueueDescriptor> additional = descriptor.additionalQueues(INSTANCE);
        assertThat(additional)
            .containsExactly(new QueueDescriptor(
                CONTROL_QUEUE_PREFIX + ".orchestrator-status." + INSTANCE,
                Set.of("event.metric.status-full.*.swarm-controller.*", "event.metric.status-delta.*.swarm-controller.*")));

        ControlPlaneRouteCatalog routes = descriptor.routes();
        assertThat(routes.lifecycleEvents())
            .containsExactlyInAnyOrder("event.outcome.#");
        assertThat(routes.statusEvents())
            .containsExactlyInAnyOrder("event.metric.status-full.*.swarm-controller.*", "event.metric.status-delta.*.swarm-controller.*");
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

    private static WorkerControlPlaneTopologyDescriptor workerDescriptor(String role) {
        return new WorkerControlPlaneTopologyDescriptor(role, SETTINGS);
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
            ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, SWARM_ID, role, "ALL"),
            ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, SWARM_ID, role, instanceSegment),
            ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, SWARM_ID, "ALL", "ALL")
        );
    }

    private static Set<String> expectedWorkerStatusSignals(String role, String instanceSegment) {
        return Set.of(
            ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, "ALL", role, "ALL"),
            ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, SWARM_ID, role, "ALL"),
            ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, SWARM_ID, role, instanceSegment),
            ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, SWARM_ID, "ALL", "ALL")
        );
    }

    private static Set<String> expectedSwarmControllerSignals(String instanceSegment) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(expectedSwarmControllerConfigSignals(instanceSegment));
        merged.addAll(expectedSwarmControllerStatusSignals(instanceSegment));
        merged.add(ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_START, SWARM_ID, "swarm-controller", instanceSegment));
        merged.add(ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_PLAN, SWARM_ID, "swarm-controller", instanceSegment));
        merged.add(ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_TEMPLATE, SWARM_ID, "swarm-controller", instanceSegment));
        merged.add(ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_STOP, SWARM_ID, "swarm-controller", instanceSegment));
        merged.add(ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_REMOVE, SWARM_ID, "swarm-controller", instanceSegment));
        return Set.copyOf(merged);
    }

    private static Set<String> expectedSwarmControllerConfigSignals(String instanceSegment) {
        return Set.of(
            ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, "ALL", "swarm-controller", "ALL"),
            ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, SWARM_ID, "swarm-controller", "ALL"),
            ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, SWARM_ID, "swarm-controller", instanceSegment),
            ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, SWARM_ID, "ALL", "ALL")
        );
    }

    private static Set<String> expectedSwarmControllerStatusSignals(String instanceSegment) {
        return Set.of(
            ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, "ALL", "swarm-controller", "ALL"),
            ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, SWARM_ID, "swarm-controller", "ALL"),
            ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, SWARM_ID, "swarm-controller", instanceSegment),
            ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, SWARM_ID, "ALL", "ALL")
        );
    }
}
