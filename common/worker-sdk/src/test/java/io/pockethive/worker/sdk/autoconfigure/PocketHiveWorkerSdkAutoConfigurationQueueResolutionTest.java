package io.pockethive.worker.sdk.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.worker.sdk.api.MessageWorker;
import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.config.PocketHiveWorker;
import io.pockethive.worker.sdk.config.WorkerType;
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
            "pockethive.control-plane.swarm-id=swarm-alpha",
            "pockethive.control-plane.queues.in=swarm-alpha.in",
            "pockethive.control-plane.queues.out=swarm-alpha.out"
        )
        .withUserConfiguration(TestWorkerConfiguration.class, PocketHiveWorkerSdkAutoConfiguration.class);

    @Test
    void resolvesQueuesFromControlPlaneProperties() {
        contextRunner.run(context -> {
            WorkerRegistry registry = context.getBean(WorkerRegistry.class);
            Optional<WorkerDefinition> definition = registry.find("aliasWorker");
            assertThat(definition).isPresent();
            assertThat(definition.get().inQueue()).isEqualTo("swarm-alpha.in");
            assertThat(definition.get().outQueue()).isEqualTo("swarm-alpha.out");
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestWorkerConfiguration {

        @Bean
        AliasWorker aliasWorker() {
            return new AliasWorker();
        }
    }

    @PocketHiveWorker(role = "processor", type = WorkerType.MESSAGE, inQueue = "in", outQueue = "out")
    static class AliasWorker implements MessageWorker {

        @Override
        public WorkResult onMessage(WorkMessage in, WorkerContext context) {
            return WorkResult.none();
        }
    }
}
