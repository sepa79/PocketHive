package io.pockethive.controlplane.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.Topology;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.worker.WorkerControlPlane;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class WorkerControlPlaneAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            ControlPlaneCommonAutoConfiguration.class,
            WorkerControlPlaneAutoConfiguration.class))
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withBean(AmqpTemplate.class, () -> org.mockito.Mockito.mock(AmqpTemplate.class))
        .withPropertyValues(
            "pockethive.control-plane.worker.role=generator",
            "pockethive.control-plane.worker.instance-id=gen-1",
            "pockethive.control-plane.swarm-id=swarm-alpha",
            "pockethive.control-plane.exchange=ph.control.worker");

    @Test
    void registersWorkerInfrastructure() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(WorkerControlPlane.class);
            assertThat(context).hasSingleBean(ControlPlanePublisher.class);
            TopicExchange exchange = context.getBean("controlPlaneExchange", TopicExchange.class);
            assertThat(exchange.getName()).isEqualTo("ph.control.worker");

            ControlPlaneIdentity identity = context.getBean("workerControlPlaneIdentity", ControlPlaneIdentity.class);
            assertThat(identity.swarmId()).isEqualTo("swarm-alpha");
            assertThat(identity.instanceId()).isEqualTo("gen-1");
            assertThat(identity.role()).isEqualTo("generator");

            Declarables declarables = context.getBean("workerControlPlaneDeclarables", Declarables.class);
            Optional<Queue> queue = declarables.getDeclarables().stream()
                .filter(Queue.class::isInstance)
                .map(Queue.class::cast)
                .findFirst();
            assertThat(queue).isPresent();
            String expectedQueue = Topology.CONTROL_QUEUE + "." + Topology.SWARM_ID + ".generator.gen-1";
            assertThat(queue.get().getName()).isEqualTo(expectedQueue);
        });
    }

    @Test
    void skipsEverythingWhenControlPlaneDisabled() {
        contextRunner
            .withPropertyValues("pockethive.control-plane.enabled=false")
            .run(context -> {
                assertThat(context).doesNotHaveBean(WorkerControlPlane.class);
                assertThat(context).doesNotHaveBean(ControlPlanePublisher.class);
                assertThat(context).doesNotHaveBean("controlPlaneExchange");
                assertThat(context).doesNotHaveBean(ControlPlaneTopologyDeclarableFactory.class);
            });
    }

    @Test
    void skipsListenerWhenDisabled() {
        contextRunner
            .withPropertyValues("pockethive.control-plane.worker.listener.enabled=false")
            .run(context -> assertThat(context).doesNotHaveBean(WorkerControlPlane.class));
    }

    @Test
    void skipsDeclarablesWhenDisabled() {
        contextRunner
            .withPropertyValues("pockethive.control-plane.worker.declare-topology=false")
            .run(context -> {
                Declarables declarables = context.getBean("workerControlPlaneDeclarables", Declarables.class);
                assertThat(declarables.getDeclarables()).isEmpty();
            });
    }
}
