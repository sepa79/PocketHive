package io.pockethive.worker.sdk.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.work.api.PocketHiveWorkerFunction;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerContext;
import io.pockethive.work.api.PocketHiveWorker;
import org.junit.jupiter.api.Test;
import io.pockethive.rabbit.api.RabbitTopologySpec;
import io.pockethive.rabbit.api.RabbitPublisher;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class WorkControlCompositionTest {

    private ApplicationContextRunner workerContext() {
        return new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration.class, PocketHiveWorkerSdkAutoConfiguration.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(io.pockethive.rabbit.api.RabbitTransportBeans.CONTROL_PUBLISHER, RabbitPublisher.class, () -> org.mockito.Mockito.mock(RabbitPublisher.class))
            .withBean(io.pockethive.rabbit.api.RabbitTransportBeans.WORK_PUBLISHER, RabbitPublisher.class, () -> mock(RabbitPublisher.class))
            .withBean("compositionWorker", CompositionWorker.class)
            .withPropertyValues(
                "spring.rabbitmq.listener.simple.auto-startup=false",
                "pockethive.control-plane.worker.role=generator",
                "pockethive.control-plane.worker.enabled=true",
                "pockethive.control-plane.manager.enabled=false",
                "pockethive.control-plane.instance-id=composition-worker",
                "pockethive.control-plane.swarm-id=composition-swarm",
                "pockethive.control-plane.exchange=composition.control",
                "pockethive.control-plane.control-queue-prefix=composition.control",
                "pockethive.inputs.type=SCHEDULER",
                "pockethive.inputs.scheduler.rate-per-sec=0",
                "pockethive.inputs.scheduler.max-messages=0",
                "pockethive.outputs.type=NONE");
    }

    @Test
    void schedulerWithoutOutputStartsWithControlDeclarationsAndNoWorkRabbitSettings() {
        workerContext().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean("workerControlPlaneDeclarables", RabbitTopologySpec.class).queues()).isNotEmpty();
            assertThat(context.getEnvironment().getProperty("pockethive.outputs.rabbit.exchange")).isNull();
            assertThat(context.getEnvironment().getProperty("pockethive.inputs.rabbit.queue")).isNull();
        });
    }

    @PocketHiveWorker
    static class CompositionWorker implements PocketHiveWorkerFunction {
        @Override
        public WorkItem onMessage(WorkItem input, WorkerContext context) {
            return null;
        }
    }
}
