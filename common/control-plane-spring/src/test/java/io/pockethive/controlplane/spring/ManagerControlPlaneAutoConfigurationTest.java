package io.pockethive.controlplane.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.Topology;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.manager.ManagerControlPlane;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ManagerControlPlaneAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            ControlPlaneCommonAutoConfiguration.class,
            ManagerControlPlaneAutoConfiguration.class))
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withBean(RabbitTemplate.class, () -> org.mockito.Mockito.mock(RabbitTemplate.class))
        .withPropertyValues(
            "pockethive.control-plane.manager.role=orchestrator",
            "pockethive.control-plane.manager.instance-id=orch-1",
            "pockethive.control-plane.swarm-id=swarm-beta",
            "pockethive.control-plane.exchange=ph.control.manager");

    @Test
    void registersManagerInfrastructure() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ManagerControlPlane.class);
            assertThat(context).hasSingleBean(ControlPlanePublisher.class);

            TopicExchange exchange = context.getBean("controlPlaneExchange", TopicExchange.class);
            assertThat(exchange.getName()).isEqualTo("ph.control.manager");

            TopicExchange workExchange = context.getBean("swarmWorkExchange", TopicExchange.class);
            assertThat(workExchange.getName()).isEqualTo(Topology.EXCHANGE);

            ControlPlaneIdentity identity = context.getBean("managerControlPlaneIdentity", ControlPlaneIdentity.class);
            assertThat(identity.swarmId()).isEqualTo("swarm-beta");
            assertThat(identity.instanceId()).isEqualTo("orch-1");
            assertThat(identity.role()).isEqualTo("orchestrator");

            Declarables declarables = context.getBean("managerControlPlaneDeclarables", Declarables.class);
            List<Queue> queues = declarables.getDeclarables().stream()
                .filter(Queue.class::isInstance)
                .map(Queue.class::cast)
                .toList();
            assertThat(queues)
                .extracting(Queue::getName)
                .contains("ph.control.orchestrator.orch-1", "ph.control.orchestrator-status.orch-1");
        });
    }

    @Test
    void disablesDeclarablesWhenRequested() {
        contextRunner
            .withPropertyValues("pockethive.control-plane.manager.declare-topology=false")
            .run(context -> {
                Declarables declarables = context.getBean("managerControlPlaneDeclarables", Declarables.class);
                assertThat(declarables.getDeclarables()).isEmpty();
            });
    }

    @Test
    void skipsPublisherWhenDisabled() {
        contextRunner
            .withPropertyValues("pockethive.control-plane.publisher.enabled=false")
            .run(context -> {
                assertThat(context).doesNotHaveBean(ControlPlanePublisher.class);
                assertThat(context).doesNotHaveBean(ManagerControlPlane.class);
            });
    }
}
