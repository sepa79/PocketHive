package io.pockethive.worker.sdk.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.worker.sdk.api.PocketHiveWorkerFunction;
import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.config.PocketHiveWorker;
import io.pockethive.worker.sdk.config.WorkInputConfig;
import io.pockethive.worker.sdk.input.WorkInput;
import io.pockethive.worker.sdk.input.WorkInputFactory;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRegistry;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class PocketHiveWorkerSdkAutoConfigurationQueueResolutionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withPropertyValues(
            "pockethive.control-plane.worker.enabled=true",
            "pockethive.control-plane.worker.role=processor",
            "pockethive.control-plane.manager.enabled=false",
            "pockethive.control-plane.instance-id=instance-1",
            "pockethive.control-plane.swarm-id=Swarm-Alpha",
            "pockethive.control-plane.exchange=swarm-alpha.control",
            "pockethive.control-plane.control-queue-prefix=ph.control",
            "pockethive.control-plane.traffic-exchange=swarm-alpha.hive",
            "pockethive.control-plane.queues.in=swarm-alpha.in",
            "pockethive.control-plane.queues.out=swarm-alpha.out",
            "pockethive.control-plane.swarm-controller.rabbit.logs-exchange=swarm-alpha.logs",
            "pockethive.control-plane.swarm-controller.rabbit.logging.enabled=false",
            "management.prometheus.metrics.export.pushgateway.enabled=true",
            "management.prometheus.metrics.export.pushgateway.base-url=http://pushgateway:9091",
            "management.prometheus.metrics.export.pushgateway.push-rate=PT30S",
            "management.prometheus.metrics.export.pushgateway.job=worker-sdk-test",
            "management.prometheus.metrics.export.pushgateway.shutdown-operation=DELETE",
            "management.prometheus.metrics.export.pushgateway.grouping-key.instance=test-worker"
        )
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withUserConfiguration(TestWorkerConfiguration.class, PocketHiveWorkerSdkAutoConfiguration.class);

    @Test
    void resolvesQueuesFromControlPlaneProperties() {
        contextRunner.run(context -> {
            WorkerRegistry registry = context.getBean(WorkerRegistry.class);
            Optional<WorkerDefinition> definition = registry.find("aliasWorker");
            assertThat(definition).isPresent();
            assertThat(definition.get().inQueue()).isEqualTo("swarm-alpha.in");
            assertThat(definition.get().outQueue()).isEqualTo("swarm-alpha.out");
            assertThat(definition.get().exchange()).isEqualTo("swarm-alpha.hive");
        });
    }

    @Test
    void registersControlQueueAliasFromProperties() {
        contextRunner.run(context -> {
            WorkerRegistry registry = context.getBean(WorkerRegistry.class);
            Optional<WorkerDefinition> definition = registry.find("controlQueueWorker");
            assertThat(definition).isPresent();
            assertThat(definition.get().inQueue()).isEqualTo("ph.control.Swarm-Alpha.processor.instance-1");
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestWorkerConfiguration {

        @Bean
        AliasWorker aliasWorker() {
            return new AliasWorker();
        }

        @Bean
        ControlQueueWorker controlQueueWorker() {
            return new ControlQueueWorker();
        }

        @Bean
        WorkInputFactory testWorkInputFactory() {
            return new WorkInputFactory() {
                @Override
                public boolean supports(WorkerDefinition definition) {
                    return true;
                }

                @Override
                public WorkInput create(WorkerDefinition definition, WorkInputConfig config) {
                    return new WorkInput() {};
                }
            };
        }
    }

    @PocketHiveWorker(role = "processor", inQueue = "in", outQueue = "out")
    static class AliasWorker implements PocketHiveWorkerFunction {

        @Override
        public WorkResult onMessage(WorkMessage in, WorkerContext context) {
            return WorkResult.none();
        }
    }

    @PocketHiveWorker(role = "processor", inQueue = "control", outQueue = "out")
    static class ControlQueueWorker implements PocketHiveWorkerFunction {

        @Override
        public WorkResult onMessage(WorkMessage in, WorkerContext context) {
            return WorkResult.none();
        }
    }
}
