package io.pockethive.controlplane.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.Topology;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.topology.ControlPlaneRouteCatalog;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.ControlQueueDescriptor;
import io.pockethive.controlplane.worker.WorkerControlPlane;
import java.util.Optional;
import org.springframework.beans.factory.BeanCreationException;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class WorkerControlPlaneAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            ControlPlaneCommonAutoConfiguration.class,
            WorkerControlPlaneAutoConfiguration.class))
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withBean(RabbitTemplate.class, () -> org.mockito.Mockito.mock(RabbitTemplate.class))
        .withPropertyValues(
            "pockethive.control-plane.worker.role=generator",
            "pockethive.control-plane.instance-id=gen-1",
            "pockethive.control-plane.swarm-id=swarm-alpha",
            "pockethive.control-plane.exchange=ph.control.worker",
            "pockethive.control-plane.traffic-exchange=ph.swarm-alpha.hive",
            "pockethive.control-plane.queues.generator=ph.swarm-alpha.gen",
            "pockethive.control-plane.queues.moderator=ph.swarm-alpha.mod",
            "pockethive.control-plane.swarm-controller.rabbit.logs-exchange=ph.logs",
            "pockethive.control-plane.swarm-controller.rabbit.logging.enabled=true",
            "pockethive.control-plane.swarm-controller.metrics.pushgateway.enabled=true",
            "pockethive.control-plane.swarm-controller.metrics.pushgateway.base-url=http://push:9091",
            "pockethive.control-plane.swarm-controller.metrics.pushgateway.push-rate=PT30S",
            "pockethive.control-plane.swarm-controller.metrics.pushgateway.shutdown-operation=DELETE");

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

            String queueName = context.getBean("workerControlQueueName", String.class);
            assertThat(queueName).isEqualTo(expectedQueue);
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

    @Test
    void doesNotBindTrafficQueuesToTrafficExchange() {
        contextRunner
            .withPropertyValues("pockethive.control-plane.worker.role=moderator")
            .run(context -> {
                Declarables declarables = context.getBean("workerControlPlaneDeclarables", Declarables.class);
                assertThat(declarables.getDeclarables()).isNotEmpty();

                Optional<Binding> trafficBinding = declarables.getDeclarables().stream()
                    .filter(Binding.class::isInstance)
                    .map(Binding.class::cast)
                    .filter(binding -> Topology.GEN_QUEUE.equals(binding.getDestination()))
                    .findFirst();

                assertThat(trafficBinding).isEmpty();
            });
    }

    @Test
    void failsWhenTrafficExchangeMissing() {
        contextRunner
            .withPropertyValues(
                "pockethive.control-plane.traffic-exchange=${POCKETHIVE_CONTROL_PLANE_TRAFFIC_EXCHANGE}")
            .run(context -> {
                assertThat(context).hasFailed();
                Throwable failure = context.getStartupFailure();
                assertThat(failure).isInstanceOf(BeanCreationException.class);
                assertThat(failure).hasRootCauseInstanceOf(IllegalArgumentException.class);
                assertThat(failure).hasRootCauseMessage(
                    "pockethive.control-plane.traffic-exchange must resolve to a concrete value, but was ${POCKETHIVE_CONTROL_PLANE_TRAFFIC_EXCHANGE}");
            });
    }

    @Test
    void failsFastWhenControlQueueDescriptorMissing() {
        contextRunner
            .withPropertyValues("pockethive.control-plane.worker.role=test-role")
            .withUserConfiguration(MissingQueueDescriptorConfiguration.class)
            .run(context -> {
                assertThat(context).hasFailed();
                Throwable failure = context.getStartupFailure();
                assertThat(failure).isInstanceOf(BeanCreationException.class);
                assertThat(failure).hasRootCauseInstanceOf(IllegalStateException.class);
                assertThat(failure).hasRootCauseMessage(
                    "Control queue descriptor is missing for worker role test-role");
            });
    }

    @Configuration(proxyBeanMethods = false)
    static class MissingQueueDescriptorConfiguration {

        @Bean("workerControlPlaneTopologyDescriptor")
        ControlPlaneTopologyDescriptor missingControlQueueDescriptor() {
            return new ControlPlaneTopologyDescriptor() {
                @Override
                public String role() {
                    return "test-role";
                }

                @Override
                public Optional<ControlQueueDescriptor> controlQueue(String instanceId) {
                    return Optional.empty();
                }

                @Override
                public ControlPlaneRouteCatalog routes() {
                    return ControlPlaneRouteCatalog.empty();
                }
            };
        }
    }
}
