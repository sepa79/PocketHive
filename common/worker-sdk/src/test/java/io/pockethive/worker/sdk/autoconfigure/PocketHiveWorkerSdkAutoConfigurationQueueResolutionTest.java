package io.pockethive.worker.sdk.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.work.api.PocketHiveWorkerFunction;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerContext;
import io.pockethive.work.api.PocketHiveWorker;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRegistry;
import io.pockethive.worker.sdk.testing.ControlPlaneTestFixtures;
import java.util.Optional;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;
import io.pockethive.rabbit.api.RabbitPublisher;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class PocketHiveWorkerSdkAutoConfigurationQueueResolutionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(io.pockethive.rabbit.work.RabbitWorkAutoConfiguration.class))
        .withPropertyValues(defaultProperties())
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withBean(io.pockethive.rabbit.api.RabbitTransportBeans.CONTROL_PUBLISHER, RabbitPublisher.class, () -> org.mockito.Mockito.mock(RabbitPublisher.class))
            .withBean(io.pockethive.rabbit.api.RabbitTransportBeans.WORK_PUBLISHER, RabbitPublisher.class, () -> Mockito.mock(RabbitPublisher.class))
        .withBean(io.pockethive.rabbit.api.RabbitListeners.class, () -> Mockito.mock(io.pockethive.rabbit.api.RabbitListeners.class))
        .withUserConfiguration(TestWorkerConfiguration.class, PocketHiveWorkerSdkAutoConfiguration.class);

    @ParameterizedTest
    @CsvSource({
        "RABBITMQ,RABBITMQ", "' RaBbItMq ',RABBITMQ",
        "RABBITMQ,' RaBbItMq '", "' RABBITMQ ',' RABBITMQ '"
    })
    void bindsIoConfigurationIntoWorkerDefinition(String input, String output) {
        contextRunner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
            new SystemEnvironmentPropertySource("selection", Map.of(
                "POCKETHIVE_INPUTS_TYPE", input, "POCKETHIVE_OUTPUTS_TYPE", output)))).run(context -> {
            assertThat(context).hasNotFailed();
            WorkerRegistry registry = context.getBean(WorkerRegistry.class);
            Optional<WorkerDefinition> definition = registry.find("processorWorker");
            assertThat(definition).isPresent();
            assertThat(definition.get().io().inboundQueue()).isEqualTo("ph.swarm-alpha.mod");
            assertThat(definition.get().io().outboundQueue()).isEqualTo("ph.swarm-alpha.final");
            assertThat(definition.get().io().outboundExchange()).isEqualTo("ph.swarm-alpha.hive");
        });
    }

    @Test
    void failsWhenRabbitInputQueueMissing() {
        contextRunner
            .withPropertyValues(
                "pockethive.control-plane.worker.declare-topology=false",
                "pockethive.inputs.rabbit.queue=")
            .run(context -> {
                assertThat(context).hasFailed();
                Throwable failure = context.getStartupFailure();
                assertThat(failure).isInstanceOf(BeanCreationException.class);
                assertThat(failure).hasRootCauseInstanceOf(io.pockethive.work.config.WorkConfigurationException.class);
                assertThat(failure.getCause().getMessage())
                    .contains("inputs.rabbit.queue");
            });
    }

    @Test
    void failsWhenRabbitOutputRoutingMissing() {
        contextRunner
            .withPropertyValues(
                "pockethive.control-plane.worker.declare-topology=false",
                "pockethive.outputs.rabbit.routingKey=")
            .run(context -> {
                assertThat(context).hasFailed();
                Throwable failure = context.getStartupFailure();
                assertThat(failure).isInstanceOf(BeanCreationException.class);
                assertThat(failure).hasRootCauseInstanceOf(io.pockethive.work.config.WorkConfigurationException.class);
                assertThat(failure.getCause().getMessage())
                    .contains("outputs.rabbit.routingKey");
            });
    }

    private static String[] defaultProperties() {
        return new String[] {
            "pockethive.control-plane.worker.role=processor",
            "pockethive.control-plane.worker.enabled=true",
            "pockethive.control-plane.manager.enabled=false",
            "pockethive.control-plane.instance-id=instance-1",
            "pockethive.control-plane.swarm-id=Swarm-Alpha",
            "pockethive.control-plane.exchange=swarm-alpha.control",
            "pockethive.control-plane.control-queue-prefix=ph.control",
            "pockethive.inputs.type=RABBITMQ",
            "pockethive.inputs.rabbit.queue=ph.swarm-alpha.mod",
            "pockethive.outputs.type=RABBITMQ",
            "pockethive.outputs.rabbit.exchange=ph.swarm-alpha.hive",
            "pockethive.outputs.rabbit.routingKey=ph.swarm-alpha.final"
        };
    }

    @Configuration(proxyBeanMethods = false)
    static class TestWorkerConfiguration {

        @Bean
        ProcessorWorker processorWorker() {
            return new ProcessorWorker();
        }

        @Bean("workerControlPlaneTopologyDescriptor")
        ControlPlaneTopologyDescriptor workerControlPlaneTopologyDescriptor() {
            return ControlPlaneTestFixtures.workerTopology("processor");
        }
    }

    @PocketHiveWorker
    static class ProcessorWorker implements PocketHiveWorkerFunction {

        @Override
        public WorkItem onMessage(WorkItem in, WorkerContext context) {
            return null;
        }
    }
}
