package io.pockethive.worker.sdk.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.worker.sdk.api.PocketHiveWorkerFunction;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.config.PocketHiveWorker;
import io.pockethive.worker.sdk.config.WorkInputConfig;
import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.config.WorkerOutputType;
import io.pockethive.worker.sdk.input.WorkInput;
import io.pockethive.worker.sdk.input.WorkInputFactory;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRegistry;
import io.pockethive.worker.sdk.testing.ControlPlaneTestFixtures;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class PocketHiveWorkerSdkAutoConfigurationQueueResolutionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withPropertyValues(defaultProperties())
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withUserConfiguration(TestWorkerConfiguration.class, PocketHiveWorkerSdkAutoConfiguration.class);

    @Test
    void bindsIoConfigurationIntoWorkerDefinition() {
        contextRunner.run(context -> {
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
                assertThat(failure).hasRootCauseInstanceOf(IllegalStateException.class);
                assertThat(failure.getCause().getMessage())
                    .contains("pockethive.inputs.rabbit.queue");
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
                assertThat(failure).hasRootCauseInstanceOf(IllegalStateException.class);
                assertThat(failure.getCause().getMessage())
                    .contains("pockethive.outputs.rabbit.routingKey");
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
            "pockethive.control-plane.swarm-controller.rabbit.logs-exchange=ph.logs",
            "pockethive.control-plane.swarm-controller.rabbit.logging.enabled=false",
            "management.prometheus.metrics.export.pushgateway.enabled=true",
            "management.prometheus.metrics.export.pushgateway.base-url=http://pushgateway:9091",
            "management.prometheus.metrics.export.pushgateway.push-rate=PT30S",
            "management.prometheus.metrics.export.pushgateway.job=worker-sdk-test",
            "management.prometheus.metrics.export.pushgateway.shutdown-operation=DELETE",
            "management.prometheus.metrics.export.pushgateway.grouping-key.instance=test-worker",
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

        @Bean
        WorkInputFactory stubWorkInputFactory() {
            return new WorkInputFactory() {
                @Override
                public boolean supports(WorkerDefinition definition) {
                    return true;
                }

                @Override
                public WorkInput create(WorkerDefinition definition, WorkInputConfig config) {
                    return new WorkInput() { };
                }
            };
        }

        @Bean("workerControlPlaneTopologyDescriptor")
        ControlPlaneTopologyDescriptor workerControlPlaneTopologyDescriptor() {
            return ControlPlaneTestFixtures.workerTopology("processor");
        }
    }

    @PocketHiveWorker(
        input = WorkerInputType.RABBITMQ,
        output = WorkerOutputType.RABBITMQ
    )
    static class ProcessorWorker implements PocketHiveWorkerFunction {

        @Override
        public WorkItem onMessage(WorkItem in, WorkerContext context) {
            return null;
        }
    }
}
