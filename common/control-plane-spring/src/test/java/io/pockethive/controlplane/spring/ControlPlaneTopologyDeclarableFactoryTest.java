package io.pockethive.controlplane.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.Topology;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.topology.OrchestratorControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.ProcessorControlPlaneTopologyDescriptor;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.TopicExchange;

class ControlPlaneTopologyDeclarableFactoryTest {

    private static final TopicExchange CONTROL_EXCHANGE = new TopicExchange("ph.control.test");
    private static final TopicExchange WORK_EXCHANGE = new TopicExchange("ph.work.test");

    private final ControlPlaneTopologyDeclarableFactory factory = new ControlPlaneTopologyDeclarableFactory();

    @Test
    void workerDescriptorBindsTrafficQueuesToWorkExchange() {
        ProcessorControlPlaneTopologyDescriptor descriptor = new ProcessorControlPlaneTopologyDescriptor();
        String instanceId = "worker-1";
        ControlPlaneIdentity identity = new ControlPlaneIdentity(Topology.SWARM_ID, descriptor.role(), instanceId);

        Declarables declarables = factory.create(descriptor, identity, CONTROL_EXCHANGE, WORK_EXCHANGE);

        String controlQueueName = descriptor.controlQueue(instanceId).orElseThrow().name();
        Collection<String> trafficQueueNames = descriptor.additionalQueues(instanceId).stream()
            .map(queue -> queue.name())
            .toList();

        Map<String, List<Binding>> bindingsByDestination = declarables.getDeclarables().stream()
            .filter(Binding.class::isInstance)
            .map(Binding.class::cast)
            .collect(Collectors.groupingBy(Binding::getDestination));

        assertThat(bindingsByDestination).containsKey(controlQueueName);
        List<Binding> controlBindings = bindingsByDestination.get(controlQueueName);
        assertThat(controlBindings)
            .isNotEmpty()
            .allSatisfy(binding -> assertThat(binding.getExchange()).isEqualTo(CONTROL_EXCHANGE.getName()));

        for (String trafficQueueName : trafficQueueNames) {
            assertThat(bindingsByDestination).containsKey(trafficQueueName);
            List<Binding> trafficBindings = bindingsByDestination.get(trafficQueueName);
            assertThat(trafficBindings)
                .isNotEmpty()
                .allSatisfy(binding -> assertThat(binding.getExchange()).isEqualTo(WORK_EXCHANGE.getName()));
        }
    }

    @Test
    void orchestratorStatusQueueStaysOnControlExchange() {
        OrchestratorControlPlaneTopologyDescriptor descriptor = new OrchestratorControlPlaneTopologyDescriptor();
        String instanceId = "orch-1";
        ControlPlaneIdentity identity = new ControlPlaneIdentity(Topology.SWARM_ID, descriptor.role(), instanceId);

        Declarables declarables = factory.create(descriptor, identity, CONTROL_EXCHANGE, WORK_EXCHANGE);

        String statusQueue = descriptor.additionalQueues(instanceId).stream()
            .map(queue -> queue.name())
            .findFirst()
            .orElseThrow();

        List<Binding> statusBindings = declarables.getDeclarables().stream()
            .filter(Binding.class::isInstance)
            .map(Binding.class::cast)
            .filter(binding -> binding.getDestination().equals(statusQueue))
            .toList();

        assertThat(statusBindings)
            .isNotEmpty()
            .allSatisfy(binding -> assertThat(binding.getExchange()).isEqualTo(CONTROL_EXCHANGE.getName()));
    }
}
