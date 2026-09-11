package io.pockethive.swarmcontroller.infra.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.Work;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import io.pockethive.swarmcontroller.config.WorkerWorkConfigurationComposition;
import io.pockethive.swarmcontroller.infra.amqp.SwarmWorkTopologyManager;
import io.pockethive.topology.work.PrefixedWorkResourceNames;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;

class WorkTopologyConfigurationTest {
    @Test
    void provisionedResourcesMatchWorkerEnvironmentAndBootstrap() {
        var properties = mock(SwarmControllerProperties.class);
        when(properties.getTraffic()).thenReturn(new SwarmControllerProperties.Traffic(" hive ", " prefix "));
        var names = new PrefixedWorkResourceNames();
        var adapter = new WorkerWorkConfigurationComposition().workerWorkConfiguration(properties, names);
        var bee = new Bee("processor", "image", Work.ofDefaults("in", "out"), Map.of(),
            Map.of("inputs", Map.of("type", "RABBITMQ"), "outputs", Map.of("type", "RABBITMQ")));
        var result = adapter.compose(bee, bee.config(), Map.of(
            "SPRING_RABBITMQ_HOST", "broker", "SPRING_RABBITMQ_PORT", "5672",
            "SPRING_RABBITMQ_USERNAME", "user", "SPRING_RABBITMQ_PASSWORD", "secret",
            "SPRING_RABBITMQ_VIRTUAL_HOST", "/"));
        var amqp = mock(AmqpAdmin.class);
        var topology = new SwarmWorkTopologyManager(amqp, properties, names);
        var exchange = topology.declareWorkExchange();
        topology.declareWorkQueues(exchange, Set.of("in", "out"), new LinkedHashSet<>());
        var queues = ArgumentCaptor.forClass(Queue.class);
        verify(amqp, times(2)).declareQueue(queues.capture());
        assertThat(queues.getAllValues()).extracting(Queue::getName)
            .containsExactlyInAnyOrder(result.environment().get("POCKETHIVE_INPUT_RABBIT_QUEUE"),
                result.environment().get("POCKETHIVE_OUTPUT_RABBIT_ROUTING_KEY"));
        assertThat(exchange.getName()).isEqualTo(result.environment().get("POCKETHIVE_OUTPUT_RABBIT_EXCHANGE"));
        var bindings = ArgumentCaptor.forClass(Binding.class);
        verify(amqp, times(2)).declareBinding(bindings.capture());
        assertThat(bindings.getAllValues()).allSatisfy(binding -> {
            assertThat(binding.getExchange()).isEqualTo(exchange.getName());
            assertThat(binding.getRoutingKey()).isEqualTo(binding.getDestination());
        });
        var inputs = (Map<?, ?>) result.bootstrapConfig().get("inputs");
        assertThat(((Map<?, ?>) inputs.get("rabbit")).get("queue"))
            .isEqualTo(result.environment().get("POCKETHIVE_INPUT_RABBIT_QUEUE"));
        var outputs = (Map<?, ?>) result.bootstrapConfig().get("outputs");
        assertThat(((Map<?, ?>) outputs.get("rabbit")).get("routingKey"))
            .isEqualTo(result.environment().get("POCKETHIVE_OUTPUT_RABBIT_ROUTING_KEY"));
    }
}
