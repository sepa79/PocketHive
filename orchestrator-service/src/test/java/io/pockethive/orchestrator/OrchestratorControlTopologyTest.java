package io.pockethive.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.spring.ControlPlaneCommonAutoConfiguration;
import io.pockethive.controlplane.spring.ManagerControlPlaneAutoConfiguration;
import io.pockethive.controlplane.topology.OrchestratorControlPlaneTopologyDescriptor;
import io.pockethive.orchestrator.app.ControllerStatusListener;
import io.pockethive.orchestrator.app.SwarmSignalListener;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import io.pockethive.rabbit.api.RabbitBindingSpec;
import io.pockethive.rabbit.api.RabbitTopologySpec;
import io.pockethive.rabbit.api.RabbitQueueSpec;
import io.pockethive.rabbit.api.RabbitPublisher;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OrchestratorControlTopologyTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            ControlPlaneCommonAutoConfiguration.class, ManagerControlPlaneAutoConfiguration.class))
        .withUserConfiguration(OrchestratorControlQueueConfiguration.class)
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withBean(SwarmSignalListener.class, () -> mock(SwarmSignalListener.class))
        .withBean(ControllerStatusListener.class, () -> mock(ControllerStatusListener.class))
        .withBean(io.pockethive.rabbit.api.RabbitTransportBeans.CONTROL_PUBLISHER, RabbitPublisher.class, () -> mock(RabbitPublisher.class))
        .withPropertyValues(
            "pockethive.control-plane.worker.enabled=false",
            "pockethive.control-plane.manager.enabled=true",
            "pockethive.control-plane.manager.role=orchestrator",
            "pockethive.control-plane.instance-id=orch-1",
            "pockethive.control-plane.swarm-id=hive",
            "pockethive.control-plane.exchange=custom.control.exchange");

    @ParameterizedTest
    @ValueSource(strings = {"ph.control", "tenant.custom.control"})
    void listenerQueuesAndEveryBindingHaveExactlyOneSharedDeclaration(String prefix) {
        runner.withPropertyValues("pockethive.control-plane.control-queue-prefix=" + prefix).run(context -> {
            assertThat(context).hasNotFailed();
            var descriptor = context.getBean(OrchestratorControlPlaneTopologyDescriptor.class);
            var identity = context.getBean("managerControlPlaneIdentity", ControlPlaneIdentity.class);
            String controlQueue = descriptor.controlQueue(identity.instanceId()).orElseThrow().name();
            String statusQueue = descriptor.controllerStatusQueue(identity.instanceId()).name();
            assertThat(controlQueue).isEqualTo(prefix + ".orchestrator.orch-1");
            assertThat(statusQueue).isEqualTo(prefix + ".orchestrator-status.orch-1");
            assertThat(listenerQueue(context, SwarmSignalListener.class)).isEqualTo(controlQueue);
            assertThat(listenerQueue(context, ControllerStatusListener.class)).isEqualTo(statusQueue);

            List<Object> declarations = declarations(context);
            assertThat(declarations.stream().filter(RabbitQueueSpec.class::isInstance).map(RabbitQueueSpec.class::cast))
                .extracting(RabbitQueueSpec::name).containsExactlyInAnyOrder(controlQueue, statusQueue);
            List<RabbitBindingSpec> bindings = declarations.stream().filter(RabbitBindingSpec.class::isInstance)
                .map(RabbitBindingSpec.class::cast).toList();
            assertThat(bindings).hasSize(5).allSatisfy(binding ->
                assertThat(binding.exchange()).isEqualTo("custom.control.exchange"));
            assertThat(bindings.stream().filter(binding -> binding.queue().equals(controlQueue)))
                .extracting(RabbitBindingSpec::routingKey)
                .containsExactlyInAnyOrderElementsOf(descriptor.controlQueue(identity.instanceId()).orElseThrow().allBindings());
            assertThat(bindings.stream().filter(binding -> binding.queue().equals(statusQueue)))
                .extracting(RabbitBindingSpec::routingKey)
                .containsExactlyInAnyOrderElementsOf(descriptor.controllerStatusQueue(identity.instanceId()).bindings());
        });
    }

    @org.junit.jupiter.api.Test
    void receiveBindingsForwardPayloadAndReceivedRouteToTheirOwnHandlers() {
        runner.withPropertyValues("pockethive.control-plane.control-queue-prefix=custom").run(context -> {
            var message = new io.pockethive.rabbit.api.RabbitMessage("payload".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.util.Map.of(), io.pockethive.rabbit.api.RabbitMessage.TEXT, "UTF-8", true, "received.route");
            context.getBean("managerControlRabbitBinding", io.pockethive.rabbit.api.RabbitListenerBinding.class)
                .handler().accept(message);
            org.mockito.Mockito.verify(context.getBean(SwarmSignalListener.class)).handle("payload", "received.route");
            org.mockito.Mockito.verify(context.getBean(ControllerStatusListener.class), org.mockito.Mockito.never())
                .handle(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
            context.getBean("controllerStatusRabbitBinding", io.pockethive.rabbit.api.RabbitListenerBinding.class)
                .handler().accept(message);
            org.mockito.Mockito.verify(context.getBean(ControllerStatusListener.class)).handle("payload", "received.route");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"pockethive.control-plane.declare-topology", "pockethive.control-plane.manager.declare-topology"})
    void disablingSharedDeclarationsCannotBeBypassedByOrchestrator(String setting) {
        runner.withPropertyValues("pockethive.control-plane.control-queue-prefix=external", setting + "=false")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(declarations(context)).noneMatch(RabbitQueueSpec.class::isInstance).noneMatch(RabbitBindingSpec.class::isInstance);
                assertThat(listenerQueue(context, SwarmSignalListener.class)).isEqualTo("external.orchestrator.orch-1");
                assertThat(listenerQueue(context, ControllerStatusListener.class)).isEqualTo("external.orchestrator-status.orch-1");
            });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void missingSharedPrefixFailsStartup(String prefix) {
        runner.withPropertyValues("pockethive.control-plane.control-queue-prefix=" + prefix)
            .run(context -> assertThat(context).hasFailed());
    }

    private static List<Object> declarations(AssertableApplicationContext context) {
        return context.getBeansOfType(RabbitTopologySpec.class).values().stream()
            .flatMap(spec -> Stream.concat(spec.queues().stream(), spec.bindings().stream()))
            .map(value -> (Object) value).toList();
    }

    private static Object listenerQueue(AssertableApplicationContext context, Class<?> listener) throws Exception {
        String name = listener == SwarmSignalListener.class ? "managerControlRabbitBinding" : "controllerStatusRabbitBinding";
        return context.getBean(name, io.pockethive.rabbit.api.RabbitListenerBinding.class).queue();
    }
}
