package io.pockethive.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.controlplane.spring.ControlPlaneCommonAutoConfiguration;
import io.pockethive.controlplane.spring.WorkerControlPlaneAutoConfiguration;
import io.pockethive.controlplane.spring.WorkerControlPlaneProperties;
import io.pockethive.worker.sdk.testing.ControlPlaneTestFixtures;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import io.pockethive.rabbit.api.RabbitPublisher;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ProcessorTopologyProvisioningTest {

    private static final WorkerControlPlaneProperties WORKER_PROPERTIES =
        ControlPlaneTestFixtures.workerProperties("swarm-alpha", "processor", "processor-1");
    private static final Map<String, String> WORKER_QUEUES =
        ControlPlaneTestFixtures.workerQueues("swarm-alpha");
    private static final String MODERATOR_QUEUE = WORKER_QUEUES.get("moderator");
    private static final String FINAL_QUEUE = WORKER_QUEUES.get("final");
    private static final String EXCHANGE = ControlPlaneTestFixtures.hiveExchange("swarm-alpha");

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            ControlPlaneCommonAutoConfiguration.class,
            WorkerControlPlaneAutoConfiguration.class))
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withBean(RabbitPublisher.class, () -> Mockito.mock(RabbitPublisher.class))
        .withPropertyValues(
            "pockethive.control-plane.worker.role=processor",
            "pockethive.control-plane.instance-id=" + WORKER_PROPERTIES.getInstanceId(),
            "pockethive.control-plane.swarm-id=" + WORKER_PROPERTIES.getSwarmId(),
            "pockethive.control-plane.exchange=" + WORKER_PROPERTIES.getExchange(),
            "pockethive.control-plane.control-queue-prefix=" + WORKER_PROPERTIES.getControlQueuePrefix(),
            "pockethive.inputs.rabbit.queue=" + MODERATOR_QUEUE,
            "pockethive.outputs.rabbit.exchange=" + EXCHANGE,
            "pockethive.outputs.rabbit.routing-key=" + FINAL_QUEUE);

    @Test
    void processorServiceDoesNotDeclareTrafficTopology() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean("moderatedTrafficDeclarables");

            var topologies = context.getBeansOfType(io.pockethive.rabbit.api.RabbitTopologySpec.class).values();
            assertThat(topologies).isNotEmpty();
            assertThat(topologies.stream().flatMap(spec -> spec.queues().stream()))
                .noneMatch(queue -> MODERATOR_QUEUE.equals(queue.name()));
            assertThat(topologies.stream().flatMap(spec -> spec.bindings().stream()))
                .noneMatch(binding -> MODERATOR_QUEUE.equals(binding.queue())
                    || MODERATOR_QUEUE.equals(binding.routingKey()) || EXCHANGE.equals(binding.exchange()));
            assertThat(context.getBeansOfType(io.pockethive.rabbit.api.RabbitExchangeSpec.class).values())
                .noneMatch(exchange -> EXCHANGE.equals(exchange.name()));
        });
    }
}
