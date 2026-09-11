package io.pockethive.controlplane.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.topology.ControlPlaneRouteCatalog;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.ControlQueueDescriptor;
import io.pockethive.controlplane.worker.WorkerControlPlane;
import java.util.Optional;
import org.springframework.beans.factory.BeanCreationException;
import org.junit.jupiter.api.Test;
import io.pockethive.rabbit.api.RabbitTopologySpec;
import io.pockethive.rabbit.api.RabbitQueueSpec;
import io.pockethive.rabbit.api.RabbitExchangeSpec;
import io.pockethive.rabbit.api.RabbitPublisher;
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
        .withBean(io.pockethive.rabbit.api.RabbitTransportBeans.CONTROL_PUBLISHER, RabbitPublisher.class, () -> org.mockito.Mockito.mock(RabbitPublisher.class))
        .withPropertyValues(
            "pockethive.control-plane.worker.role=generator",
            "pockethive.control-plane.instance-id=gen-1",
            "pockethive.control-plane.swarm-id=swarm-alpha",
            "pockethive.control-plane.control-queue-prefix=ph.control",
            "pockethive.control-plane.exchange=ph.control.worker",
            "pockethive.inputs.rabbit.queue=ph.swarm-alpha.gen",
            "pockethive.outputs.rabbit.exchange=ph.swarm-alpha.hive",
            "pockethive.outputs.rabbit.routing-key=ph.swarm-alpha.gen");

    @Test
    void registersWorkerInfrastructure() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(WorkerControlPlane.class);
            assertThat(context).hasSingleBean(ControlPlanePublisher.class);
            RabbitExchangeSpec exchange = context.getBean("controlPlaneExchange", RabbitExchangeSpec.class);
            assertThat(exchange.name()).isEqualTo("ph.control.worker");

            ControlPlaneIdentity identity = context.getBean("workerControlPlaneIdentity", ControlPlaneIdentity.class);
            assertThat(identity.swarmId()).isEqualTo("swarm-alpha");
            assertThat(identity.instanceId()).isEqualTo("gen-1");
            assertThat(identity.role()).isEqualTo("generator");

            RabbitTopologySpec declarables = context.getBean("workerControlPlaneDeclarables", RabbitTopologySpec.class);
            var queue = declarables.queues().stream()
                .findFirst();
            assertThat(queue).isPresent();
            String expectedQueue = "ph.control.swarm-alpha.generator.gen-1";
            assertThat(queue.get().name()).isEqualTo(expectedQueue);

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
    void skipsRabbitTopologySpecWhenDisabled() {
        contextRunner
            .withPropertyValues("pockethive.control-plane.worker.declare-topology=false")
            .run(context -> {
                RabbitTopologySpec declarables = context.getBean("workerControlPlaneDeclarables", RabbitTopologySpec.class);
                assertThat(declarables.queues()).isEmpty();
                assertThat(declarables.bindings()).isEmpty();
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
