package io.pockethive.controlplane.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.manager.ManagerControlPlane;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import io.pockethive.rabbit.api.RabbitBindingSpec;
import io.pockethive.rabbit.api.RabbitTopologySpec;
import io.pockethive.rabbit.api.RabbitQueueSpec;
import io.pockethive.rabbit.api.RabbitExchangeSpec;
import io.pockethive.rabbit.api.RabbitPublisher;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ManagerControlPlaneAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            ControlPlaneCommonAutoConfiguration.class,
            ManagerControlPlaneAutoConfiguration.class))
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withBean(io.pockethive.rabbit.api.RabbitTransportBeans.CONTROL_PUBLISHER, RabbitPublisher.class, () -> org.mockito.Mockito.mock(RabbitPublisher.class))
        .withPropertyValues(
            "pockethive.control-plane.worker.enabled=false",
            "pockethive.control-plane.manager.enabled=true",
            "pockethive.control-plane.manager.role=orchestrator",
            "pockethive.control-plane.instance-id=orch-1",
            "pockethive.control-plane.swarm-id=swarm-beta",
            "pockethive.control-plane.control-queue-prefix=ph.control.manager",
            "pockethive.control-plane.exchange=ph.control.manager");

    @Test
    void registersManagerInfrastructure() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ManagerControlPlane.class);
            assertThat(context).hasSingleBean(ControlPlanePublisher.class);

            RabbitExchangeSpec exchange = context.getBean("controlPlaneExchange", RabbitExchangeSpec.class);
            assertThat(exchange.name()).isEqualTo("ph.control.manager");

            ControlPlaneIdentity identity = context.getBean("managerControlPlaneIdentity", ControlPlaneIdentity.class);
            assertThat(identity.swarmId()).isEqualTo("swarm-beta");
            assertThat(identity.instanceId()).isEqualTo("orch-1");
            assertThat(identity.role()).isEqualTo("orchestrator");

            RabbitTopologySpec declarables = context.getBean("managerControlPlaneDeclarables", RabbitTopologySpec.class);
            var queues = declarables.queues();
            assertThat(queues)
                .extracting(RabbitQueueSpec::name)
                .contains("ph.control.manager.orchestrator.orch-1", "ph.control.manager.orchestrator-status.orch-1");
        });
    }

    @Test
    void bindsManagerAdditionalQueuesToControlExchange() {
        contextRunner.run(context -> {
            RabbitTopologySpec declarables = context.getBean("managerControlPlaneDeclarables", RabbitTopologySpec.class);
            var statusBinding = declarables.bindings().stream()
                .filter(binding -> "ph.control.manager.orchestrator-status.orch-1".equals(binding.queue()))
                .findFirst();

            assertThat(statusBinding).isPresent();
            assertThat(statusBinding.get().exchange()).isEqualTo("ph.control.manager");
        });
    }

    @Test
    void disablesRabbitTopologySpecWhenRequested() {
        contextRunner
            .withPropertyValues("pockethive.control-plane.manager.declare-topology=false")
            .run(context -> {
                RabbitTopologySpec declarables = context.getBean("managerControlPlaneDeclarables", RabbitTopologySpec.class);
                assertThat(declarables.queues()).isEmpty();
                assertThat(declarables.bindings()).isEmpty();
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
