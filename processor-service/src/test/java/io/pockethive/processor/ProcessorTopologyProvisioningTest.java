package io.pockethive.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.Topology;
import io.pockethive.controlplane.spring.ControlPlaneCommonAutoConfiguration;
import io.pockethive.controlplane.spring.WorkerControlPlaneAutoConfiguration;
import java.util.Collection;
import java.util.List;
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

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            ControlPlaneCommonAutoConfiguration.class,
            WorkerControlPlaneAutoConfiguration.class))
        .withUserConfiguration(ProcessorConfig.class)
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withBean(RabbitTemplate.class, () -> Mockito.mock(RabbitTemplate.class))
        .withPropertyValues(
            "pockethive.control-plane.worker.role=processor",
            "pockethive.control-plane.worker.instance-id=processor-1",
            "pockethive.control-plane.swarm-id=swarm-alpha",
            "pockethive.control-plane.exchange=ph.control.worker");

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
                    && Topology.MOD_QUEUE.equals(queue.getName()));

            assertThat(declarables)
                .noneMatch(declarable -> declarable instanceof Binding binding
                    && (Topology.MOD_QUEUE.equals(binding.getDestination())
                        || Topology.MOD_QUEUE.equals(binding.getRoutingKey())));

            assertThat(declarables)
                .noneMatch(declarable -> declarable instanceof TopicExchange exchange
                    && Topology.EXCHANGE.equals(exchange.getName()));
        });
    }
}
