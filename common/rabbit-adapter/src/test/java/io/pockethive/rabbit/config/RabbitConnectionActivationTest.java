package io.pockethive.rabbit.config;

import io.pockethive.rabbit.api.RabbitListenerBinding;
import io.pockethive.rabbit.api.RabbitListeners;
import io.pockethive.rabbit.api.RabbitListenerState;
import io.pockethive.rabbit.api.RabbitSubscription;
import io.pockethive.rabbit.topology.RabbitResourceAutoConfiguration;
import io.pockethive.rabbit.transport.RabbitTransportAutoConfiguration;
import io.pockethive.rabbit.work.RabbitWorkAutoConfiguration;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import static org.assertj.core.api.Assertions.*;

class RabbitConnectionActivationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class, RabbitConnectionConfiguration.class,
            RabbitWorkerConnectionAutoConfiguration.class, RabbitResourceAutoConfiguration.class,
            RabbitTransportAutoConfiguration.class, RabbitWorkAutoConfiguration.class))
        .withPropertyValues("spring.rabbitmq.host=control", "spring.rabbitmq.port=5672",
            "spring.rabbitmq.username=control-user", "spring.rabbitmq.password=control-secret",
            "spring.rabbitmq.virtual-host=/control", "spring.rabbitmq.listener.simple.auto-startup=false");

    @ParameterizedTest
    @CsvSource({"SCHEDULER,NONE", "REDIS_DATASET,REDIS", "MEMORY,MEMORY", "'', ''"})
    void controlDeliveryWorksWithoutAnyWorkConnectionSettings(String input, String output) {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("selection", Map.of(
                    "POCKETHIVE_INPUTS_TYPE", input, "POCKETHIVE_OUTPUTS_TYPE", output))))
            .run(context -> {
                assertThat(context).hasNotFailed();
                var received = new AtomicReference<byte[]>();
                var listeners = context.getBean(RabbitListeners.class);
                listeners.register(new RabbitListenerBinding("control-listener", "control-queue",
                    message -> received.set(message.body()), failure -> false));
                var registry = context.getBean(RabbitListenerEndpointRegistry.class);
                var container = (AbstractMessageListenerContainer) registry.getListenerContainer("control-listener");
                ((MessageListener) container.getMessageListener()).onMessage(new Message("control".getBytes(StandardCharsets.UTF_8)));
                assertThat(received.get()).isEqualTo("control".getBytes(StandardCharsets.UTF_8));
                assertThat(listeners.state("control-listener")).isEqualTo(RabbitListenerState.STOPPED);
                assertThatThrownBy(() -> listeners.register(new RabbitSubscription("work-listener", "work-queue", 7, 1, false, false), message -> {}))
                    .isInstanceOf(org.springframework.beans.factory.NoSuchBeanDefinitionException.class);
                assertThat(listeners.state("work-listener")).isEqualTo(RabbitListenerState.NOT_REGISTERED);
            });
    }

    @ParameterizedTest
    @CsvSource({
        "POCKETHIVE_INPUTS_TYPE,RABBITMQ", "POCKETHIVE_OUTPUTS_TYPE,RABBITMQ",
        "POCKETHIVE_INPUTS_TYPE,' RaBbItMq '", "POCKETHIVE_OUTPUTS_TYPE,' RaBbItMq '"
    })
    void selectedRabbitWorkRejectsMissingWorkSettingsAtStartup(String property, String selection) {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
            new SystemEnvironmentPropertySource("selection", Map.of(property, selection)))).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("pockethive.rabbit.work settings are invalid");
        });
    }
}
