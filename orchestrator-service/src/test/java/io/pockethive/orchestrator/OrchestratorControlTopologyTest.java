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
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.config.BeanExpressionContext;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OrchestratorControlTopologyTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            ControlPlaneCommonAutoConfiguration.class, ManagerControlPlaneAutoConfiguration.class))
        .withUserConfiguration(OrchestratorControlQueueConfiguration.class)
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withBean(RabbitTemplate.class, () -> mock(RabbitTemplate.class))
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

            List<Declarable> declarations = declarations(context);
            assertThat(declarations.stream().filter(Queue.class::isInstance).map(Queue.class::cast))
                .extracting(Queue::getName).containsExactlyInAnyOrder(controlQueue, statusQueue);
            List<Binding> bindings = declarations.stream().filter(Binding.class::isInstance)
                .map(Binding.class::cast).toList();
            assertThat(bindings).hasSize(5).allSatisfy(binding ->
                assertThat(binding.getExchange()).isEqualTo("custom.control.exchange"));
            assertThat(bindings.stream().filter(binding -> binding.getDestination().equals(controlQueue)))
                .extracting(Binding::getRoutingKey)
                .containsExactlyInAnyOrderElementsOf(descriptor.controlQueue(identity.instanceId()).orElseThrow().allBindings());
            assertThat(bindings.stream().filter(binding -> binding.getDestination().equals(statusQueue)))
                .extracting(Binding::getRoutingKey)
                .containsExactlyInAnyOrderElementsOf(descriptor.controllerStatusQueue(identity.instanceId()).bindings());
            assertThat(context.getBeansOfType(Declarables.class)).containsOnlyKeys("managerControlPlaneDeclarables");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"pockethive.control-plane.declare-topology", "pockethive.control-plane.manager.declare-topology"})
    void disablingSharedDeclarationsCannotBeBypassedByOrchestrator(String setting) {
        runner.withPropertyValues("pockethive.control-plane.control-queue-prefix=external", setting + "=false")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(declarations(context)).noneMatch(Queue.class::isInstance).noneMatch(Binding.class::isInstance);
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

    private static List<Declarable> declarations(AssertableApplicationContext context) {
        return Stream.concat(
            context.getBeansOfType(Declarable.class).values().stream(),
            context.getBeansOfType(Declarables.class).values().stream().flatMap(group -> group.getDeclarables().stream()))
            .toList();
    }

    private static Object listenerQueue(AssertableApplicationContext context, Class<?> listener) throws Exception {
        String expression = listener.getMethod("handle", String.class, String.class)
            .getAnnotation(RabbitListener.class).queues()[0];
        var beanFactory = context.getSourceApplicationContext().getBeanFactory();
        return beanFactory.getBeanExpressionResolver().evaluate(expression, new BeanExpressionContext(beanFactory, null));
    }
}
