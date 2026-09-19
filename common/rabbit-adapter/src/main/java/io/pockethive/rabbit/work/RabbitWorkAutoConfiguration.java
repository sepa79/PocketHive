package io.pockethive.rabbit.work;

import io.pockethive.rabbit.api.RabbitListeners;
import io.pockethive.rabbit.api.RabbitPublisher;
import io.pockethive.rabbit.api.RabbitTransportBeans;
import io.pockethive.rabbit.config.RabbitWorkerInputCondition;
import io.pockethive.rabbit.config.RabbitWorkerOutputCondition;
import io.pockethive.rabbit.transport.RabbitTransportAutoConfiguration;
import io.pockethive.work.api.transport.WorkInputTransportFactory;
import io.pockethive.work.api.transport.WorkOutputTransportFactory;
import io.pockethive.work.config.WorkerInputType;
import io.pockethive.work.config.WorkerOutputType;
import io.pockethive.work.config.binding.WorkInputConfigProvider;
import io.pockethive.work.config.binding.WorkOutputConfigProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
/**
 * Responsibility: expose Rabbit-owned Work configuration and transport providers for composition.
 * Must not: inspect worker definitions, own Work execution or define a second settings parser.
 * Contract: RESP-WORK-IO-CONFIG — docs/architecture/runtime-responsibilities.md#resp-work-io-config.
 */
@AutoConfiguration(after = RabbitTransportAutoConfiguration.class)
public class RabbitWorkAutoConfiguration {
    @Bean WorkInputConfigProvider rabbitInputBinding() {
        return new WorkInputConfigProvider(WorkerInputType.RABBITMQ, RabbitInputProperties.class);
    }
    @Bean WorkOutputConfigProvider rabbitOutputBinding() {
        return new WorkOutputConfigProvider(WorkerOutputType.RABBITMQ, RabbitOutputProperties.class);
    }
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(RabbitListeners.class)
    @Conditional(RabbitWorkerInputCondition.class)
    WorkInputTransportFactory rabbitWorkInputFactory(RabbitListeners listeners) {
        return new RabbitWorkInputFactory(listeners);
    }
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(name = RabbitTransportBeans.WORK_PUBLISHER)
    @Conditional(RabbitWorkerOutputCondition.class)
    WorkOutputTransportFactory rabbitWorkOutputFactory(
        @org.springframework.beans.factory.annotation.Qualifier(RabbitTransportBeans.WORK_PUBLISHER)
        RabbitPublisher publisher) {
        return new RabbitWorkOutputFactory(publisher);
    }
}
