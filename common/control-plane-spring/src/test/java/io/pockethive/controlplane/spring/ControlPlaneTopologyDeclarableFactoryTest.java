package io.pockethive.controlplane.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.topology.ControlPlaneRouteCatalog;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.ControlQueueDescriptor;
import io.pockethive.controlplane.topology.QueueDescriptor;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;

class ControlPlaneTopologyDeclarableFactoryTest {

    private final ControlPlaneTopologyDeclarableFactory factory = new ControlPlaneTopologyDeclarableFactory();
    private final TopicExchange exchange = new TopicExchange("ph.control.test");
    private final ControlPlaneIdentity identity = new ControlPlaneIdentity("swarm", "role", "instance");

    @Test
    void declaresControlQueueEvenWhenTrafficQueuesPresent() {
        ControlPlaneTopologyDescriptor descriptor = new StubDescriptor(
            new ControlQueueDescriptor("control.queue", Set.of("sig"), Set.of()),
            List.of(QueueDescriptor.traffic("traffic.queue", Set.of("traffic"))));

        Declarables declarables = factory.create(descriptor, identity, exchange);

        assertThat(queues(declarables))
            .singleElement()
            .satisfies(queue -> assertThat(queue.getName()).isEqualTo("control.queue"));
        assertThat(bindings(declarables))
            .extracting(Binding::getRoutingKey)
            .containsExactly("sig");
    }

    @Test
    void declaresAdditionalControlScopedQueues() {
        QueueDescriptor controlScoped = new QueueDescriptor("extra.queue", Set.of("extra"));
        ControlPlaneTopologyDescriptor descriptor = new StubDescriptor(
            new ControlQueueDescriptor("control.queue", Set.of("sig"), Set.of()),
            List.of(controlScoped));

        Declarables declarables = factory.create(descriptor, identity, exchange);

        assertThat(queues(declarables))
            .extracting(Queue::getName)
            .containsExactlyInAnyOrder("control.queue", "extra.queue");
        assertThat(bindings(declarables))
            .extracting(Binding::getRoutingKey)
            .containsExactlyInAnyOrder("sig", "extra");
    }

    private static List<Queue> queues(Declarables declarables) {
        return declarables.getDeclarables().stream()
            .filter(Queue.class::isInstance)
            .map(Queue.class::cast)
            .toList();
    }

    private static List<Binding> bindings(Declarables declarables) {
        return declarables.getDeclarables().stream()
            .filter(Binding.class::isInstance)
            .map(Binding.class::cast)
            .toList();
    }

    private record StubDescriptor(Optional<ControlQueueDescriptor> control,
                                   Collection<QueueDescriptor> additional)
        implements ControlPlaneTopologyDescriptor {

        private StubDescriptor(ControlQueueDescriptor controlQueue, Collection<QueueDescriptor> additional) {
            this(Optional.ofNullable(controlQueue), additional);
        }

        @Override
        public String role() {
            return "role";
        }

        @Override
        public Optional<ControlQueueDescriptor> controlQueue(String instanceId) {
            return control;
        }

        @Override
        public Collection<QueueDescriptor> additionalQueues(String instanceId) {
            return additional;
        }

        @Override
        public ControlPlaneRouteCatalog routes() {
            return ControlPlaneRouteCatalog.empty();
        }
    }
}
