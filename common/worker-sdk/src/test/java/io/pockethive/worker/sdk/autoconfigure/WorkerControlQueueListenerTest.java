package io.pockethive.worker.sdk.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Collection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = WorkerControlQueueListenerTest.TestConfig.class)
class WorkerControlQueueListenerTest {

    @Autowired
    private RabbitListenerEndpointRegistry registry;

    @Test
    void controlListenerUsesAutoAckFactory() {
        Collection<MessageListenerContainer> containers = registry.getListenerContainers();
        assertThat(containers).hasSize(1);

        MessageListenerContainer container = containers.iterator().next();
        assertThat(container).isInstanceOf(SimpleMessageListenerContainer.class);
        SimpleMessageListenerContainer simpleContainer = (SimpleMessageListenerContainer) container;
        assertThat(simpleContainer.getAcknowledgeMode()).isEqualTo(AcknowledgeMode.AUTO);
    }

    @EnableRabbit
    @Configuration
    static class TestConfig {

        @Bean
        SimpleRabbitListenerContainerFactoryConfigurer simpleRabbitListenerContainerFactoryConfigurer() {
            return new SimpleRabbitListenerContainerFactoryConfigurer(new RabbitProperties());
        }

        @Bean
        ConnectionFactory connectionFactory() {
            return mock(ConnectionFactory.class);
        }

        @Bean
        SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
                SimpleRabbitListenerContainerFactoryConfigurer configurer,
                ConnectionFactory connectionFactory) {
            SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
            configurer.configure(factory, connectionFactory);
            factory.setAutoStartup(false);
            return factory;
        }

        @Bean
        SimpleRabbitListenerContainerFactory moderatorManualAckListenerContainerFactory(
                SimpleRabbitListenerContainerFactoryConfigurer configurer,
                ConnectionFactory connectionFactory) {
            SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
            configurer.configure(factory, connectionFactory);
            factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
            factory.setAutoStartup(false);
            return factory;
        }

        @Bean
        WorkerControlPlaneRuntime controlPlaneRuntime() {
            return mock(WorkerControlPlaneRuntime.class);
        }

        @Bean
        WorkerControlQueueListener workerControlQueueListener(WorkerControlPlaneRuntime controlPlaneRuntime) {
            return new WorkerControlQueueListener(controlPlaneRuntime);
        }

        @Bean
        String workerControlQueueName() {
            return "worker-control";
        }
    }
}
