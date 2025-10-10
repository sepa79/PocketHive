package io.pockethive.controlplane.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.Topology;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.OrchestratorControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.ProcessorControlPlaneTopologyDescriptor;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.TopicExchange;

class ControlPlaneTopologyDeclarableFactoryTest {

    private final ControlPlaneTopologyDeclarableFactory factory = new ControlPlaneTopologyDeclarableFactory();
    private final TopicExchange controlExchange = ExchangeBuilder.topicExchange(Topology.CONTROL_EXCHANGE)
        .durable(true)
        .build();
    private final TopicExchange workExchange = ExchangeBuilder.topicExchange(Topology.EXCHANGE)
        .durable(true)
        .build();

    @Test
    void workerTrafficQueuesBindToWorkExchange() {
        ControlPlaneTopologyDescriptor descriptor = new ProcessorControlPlaneTopologyDescriptor();
        ControlPlaneIdentity identity = new ControlPlaneIdentity("swarm-alpha", descriptor.role(), "proc-1");

        Declarables declarables = factory.create(descriptor, identity, controlExchange, workExchange);

        List<Binding> trafficBindings = declarables.getDeclarables().stream()
            .filter(Binding.class::isInstance)
            .map(Binding.class::cast)
            .filter(binding -> binding.getDestination().equals(Topology.MOD_QUEUE))
            .toList();

        assertThat(trafficBindings).isNotEmpty();
        assertThat(trafficBindings)
            .allSatisfy(binding -> assertThat(binding.getExchange()).isEqualTo(workExchange.getName()));
    }

    @Test
    void orchestratorStatusQueuesBindToControlExchange() {
        ControlPlaneTopologyDescriptor descriptor = new OrchestratorControlPlaneTopologyDescriptor();
        ControlPlaneIdentity identity = new ControlPlaneIdentity("swarm-alpha", descriptor.role(), "orch-1");

        Declarables declarables = factory.create(descriptor, identity, controlExchange, workExchange);

        String statusQueue = Topology.CONTROL_QUEUE + ".orchestrator-status." + identity.instanceId();

        List<Binding> statusBindings = declarables.getDeclarables().stream()
            .filter(Binding.class::isInstance)
            .map(Binding.class::cast)
            .filter(binding -> binding.getDestination().equals(statusQueue))
            .toList();

        assertThat(statusBindings).isNotEmpty();
        assertThat(statusBindings)
            .allSatisfy(binding -> assertThat(binding.getExchange()).isEqualTo(controlExchange.getName()));
    }
}
