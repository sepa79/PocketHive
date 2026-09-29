package io.pockethive.swarmcontroller.infra.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.Work;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import io.pockethive.swarmcontroller.config.WorkerWorkConfigurationComposition;

import io.pockethive.rabbit.api.RabbitResourceNames;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import io.pockethive.rabbit.api.RabbitResources;
import io.pockethive.rabbit.api.RabbitBindingSpec;
import io.pockethive.rabbit.api.RabbitQueueSpec;

class WorkTopologyConfigurationTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void provisionedResourcesMatchWorkerEnvironmentAndBootstrap(boolean distinctRoutingKey) {
        var properties = mock(SwarmControllerProperties.class);
        var names = spy(new RabbitResourceNames());
        String outputRoute = distinctRoutingKey ? "separate.route" : "prefix.out";
        if (distinctRoutingKey) {
            doReturn(new io.pockethive.rabbit.api.RabbitWorkAddress("hive", "prefix.out", outputRoute))
                .when(names).address(anyString(), anyString(), eq("out"));
        }
        var adapter = new WorkerWorkConfigurationComposition().workerWorkConfiguration(new io.pockethive.rabbit.work.RabbitWorkBootstrapEnvironment(new io.pockethive.rabbit.api.RabbitConnectionSettings("work", 5673, "worker", "worksecret", "/work")));
        var bee = new Bee("processor", "image", Work.ofDefaults("in", "out"), Map.of(),
            Map.of("inputs", Map.of("type", "RABBITMQ"), "outputs", Map.of("type", "RABBITMQ")));
        var topology = new io.pockethive.rabbit.work.RabbitWorkTopologyResolver(names,
            swarm -> new io.pockethive.rabbit.api.RabbitWorkTopologySettings(" prefix ", " hive ")).resolve("swarm", Set.of("in", "out"));
        var result = adapter.compose(bee, bee.config(), Map.of(
            "POCKETHIVE_RABBIT_WORK_HOST", "work", "POCKETHIVE_RABBIT_WORK_PORT", "5673",
        "POCKETHIVE_RABBIT_WORK_USERNAME", "worker", "POCKETHIVE_RABBIT_WORK_PASSWORD", "worksecret",
        "POCKETHIVE_RABBIT_WORK_VIRTUAL_HOST", "/work", "SPRING_RABBITMQ_HOST", "broker", "SPRING_RABBITMQ_PORT", "5672",
            "SPRING_RABBITMQ_USERNAME", "user", "SPRING_RABBITMQ_PASSWORD", "secret",
            "SPRING_RABBITMQ_VIRTUAL_HOST", "/"), topology);
        var amqp = mock(RabbitResources.class);
        new io.pockethive.rabbit.work.RabbitWorkResources(amqp,
            new io.pockethive.rabbit.api.RabbitConnectionSettings("work", 5673, "worker", "worksecret", "/work"))
            .ensure(topology);
        String exchange = "hive";
        var queues = ArgumentCaptor.forClass(RabbitQueueSpec.class);
        verify(amqp, times(2)).declareQueue(queues.capture());
        assertThat(queues.getAllValues()).extracting(RabbitQueueSpec::name)
            .containsExactlyInAnyOrder("prefix.in", "prefix.out");
        assertThat(result.environment()).containsEntry("POCKETHIVE_INPUT_RABBIT_QUEUE", "prefix.in")
            .containsEntry("POCKETHIVE_OUTPUT_RABBIT_ROUTING_KEY", outputRoute);
        assertThat(exchange).isEqualTo(result.environment().get("POCKETHIVE_OUTPUT_RABBIT_EXCHANGE"));
        var bindings = ArgumentCaptor.forClass(RabbitBindingSpec.class);
        verify(amqp, times(2)).bind(bindings.capture());
        assertThat(bindings.getAllValues()).allSatisfy(binding -> {
            assertThat(binding.exchange()).isEqualTo(exchange);
            assertThat(binding.routingKey()).isEqualTo(binding.queue().equals("prefix.out") ? outputRoute : "prefix.in");
        });
        var inputs = (Map<?, ?>) result.bootstrapConfig().get("inputs");
        assertThat(((Map<?, ?>) inputs.get("rabbit")).get("queue"))
            .isEqualTo(result.environment().get("POCKETHIVE_INPUT_RABBIT_QUEUE"));
        var outputs = (Map<?, ?>) result.bootstrapConfig().get("outputs");
        assertThat(((Map<?, ?>) outputs.get("rabbit")).get("routingKey"))
            .isEqualTo(result.environment().get("POCKETHIVE_OUTPUT_RABBIT_ROUTING_KEY"));
    }
}
