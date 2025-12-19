package io.pockethive.controlplane.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.ConditionalRejectingErrorHandler;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.junit.jupiter.api.Test;
import org.springframework.util.ErrorHandler;

class ControlPlaneRabbitPoisonMessageCustomizerTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ControlPlaneCommonAutoConfiguration.class))
        .withPropertyValues(
            "pockethive.control-plane.exchange=ph.control",
            "pockethive.control-plane.control-queue-prefix=ph.control",
            "pockethive.control-plane.swarm-id=test-swarm",
            "pockethive.control-plane.instance-id=test-instance")
        .withBean(RabbitTemplate.class, () -> org.mockito.Mockito.mock(RabbitTemplate.class))
        .withBean(SimpleRabbitListenerContainerFactory.class, () -> {
            SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
            factory.setConnectionFactory(org.mockito.Mockito.mock(ConnectionFactory.class));
            return factory;
        });

    @Test
    void configuresErrorHandlerOnRabbitListenerContainerFactory() {
        runner.run(context -> {
            SimpleRabbitListenerContainerFactory factory = context.getBean(SimpleRabbitListenerContainerFactory.class);
            ErrorHandler handler = readErrorHandler(factory);
            assertThat(handler).isInstanceOf(ConditionalRejectingErrorHandler.class);
        });
    }

    @Test
    void canDisableCustomizerViaProperty() {
        runner.withPropertyValues("pockethive.control-plane.rabbit.poison-messages.enabled=false")
            .run(context -> {
                SimpleRabbitListenerContainerFactory factory = context.getBean(SimpleRabbitListenerContainerFactory.class);
                assertThat(readErrorHandler(factory)).isNull();
            });
    }

    private static ErrorHandler readErrorHandler(SimpleRabbitListenerContainerFactory factory) {
        Object value = readFieldByType(factory, ErrorHandler.class);
        return value instanceof ErrorHandler handler ? handler : null;
    }

    private static Object readFieldByType(Object target, Class<?> type) {
        Class<?> current = target.getClass();
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (!type.isAssignableFrom(field.getType())) {
                    continue;
                }
                field.setAccessible(true);
                try {
                    return field.get(target);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("Failed to read field " + field.getName(), e);
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }
}
