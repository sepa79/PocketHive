package io.pockethive.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.controlplane.spring.ControlPlaneCommonAutoConfiguration;
import io.pockethive.controlplane.spring.WorkerControlPlaneAutoConfiguration;
import io.pockethive.controlplane.spring.WorkerControlPlaneProperties;
import io.pockethive.worker.sdk.testing.ControlPlaneTestFixtures;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
        .withBean(RabbitTemplate.class, () -> Mockito.mock(RabbitTemplate.class))
        .withPropertyValues(
            "pockethive.control-plane.worker.role=processor",
            "pockethive.control-plane.instance-id=" + WORKER_PROPERTIES.getInstanceId(),
            "pockethive.control-plane.swarm-id=" + WORKER_PROPERTIES.getSwarmId(),
            "pockethive.control-plane.exchange=" + WORKER_PROPERTIES.getExchange(),
            "pockethive.control-plane.control-queue-prefix=" + WORKER_PROPERTIES.getControlQueuePrefix(),
            "pockethive.inputs.rabbit.queue=" + MODERATOR_QUEUE,
            "pockethive.outputs.rabbit.exchange=" + EXCHANGE,
            "pockethive.outputs.rabbit.routing-key=" + FINAL_QUEUE,
            "pockethive.control-plane.swarm-controller.rabbit.logs-exchange=ph.logs",
            "pockethive.control-plane.swarm-controller.rabbit.logging.enabled=false");

    @Test
    void processorServiceDoesNotDeclareTrafficTopology() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean("moderatedTrafficDeclarables");

            Collection<Declarables> declarablesBeans = context.getBeansOfType(Declarables.class).values();
            assertThat(declarablesBeans).isNotEmpty();

            List<Declarable> declarables = declarablesBeans.stream()
                .flatMap(bean -> bean.getDeclarables().stream())
                .toList();

            assertThat(declarables)
                .noneMatch(declarable -> declarable instanceof Queue queue
                    && MODERATOR_QUEUE.equals(queue.getName()));

            assertThat(declarables)
                .noneMatch(declarable -> declarable instanceof Binding binding
                    && (MODERATOR_QUEUE.equals(binding.getDestination())
                        || MODERATOR_QUEUE.equals(binding.getRoutingKey())));

            assertThat(declarables)
                .noneMatch(declarable -> declarable instanceof TopicExchange exchange
                    && EXCHANGE.equals(exchange.getName()));
        });
    }
}
