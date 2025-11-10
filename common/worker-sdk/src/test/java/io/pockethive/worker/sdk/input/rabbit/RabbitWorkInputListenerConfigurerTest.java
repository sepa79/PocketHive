package io.pockethive.worker.sdk.input.rabbit;

import io.pockethive.worker.sdk.config.WorkInputConfig;
import io.pockethive.worker.sdk.config.WorkOutputConfig;
import io.pockethive.worker.sdk.config.WorkerCapability;
import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.config.WorkerOutputType;
import io.pockethive.worker.sdk.input.WorkInput;
import io.pockethive.worker.sdk.input.WorkInputRegistry;
import io.pockethive.worker.sdk.runtime.WorkIoBindings;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRegistry;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerEndpoint;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpoint;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistrar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RabbitWorkInputListenerConfigurerTest {

    @Mock
    private MessageListener delegateListener;

    @Mock
    private RabbitListenerEndpointRegistrar registrar;

    @Test
    void registersRabbitEndpointsAndDispatchesMessages() {
        WorkerDefinition definition = new WorkerDefinition(
            "testWorker",
            TestWorker.class,
            WorkerInputType.RABBIT,
            "moderator",
            WorkIoBindings.of("ph.swarm.generator", "ph.swarm.moderator", "ph.control"),
            TestConfig.class,
            WorkInputConfig.class,
            WorkOutputConfig.class,
            WorkerOutputType.RABBITMQ,
            "Test worker",
            Set.of(WorkerCapability.MESSAGE_DRIVEN)
        );
        WorkerRegistry registry = new WorkerRegistry(List.of(definition));
        WorkInputRegistry inputRegistry = new WorkInputRegistry();
        inputRegistry.register(definition, new TestRabbitWorkInput(delegateListener));

        RabbitWorkInputListenerConfigurer configurer = new RabbitWorkInputListenerConfigurer(registry, inputRegistry);

        configurer.configureRabbitListeners(registrar);

        ArgumentCaptor<RabbitListenerEndpoint> captor = ArgumentCaptor.forClass(RabbitListenerEndpoint.class);
        verify(registrar).registerEndpoint(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(SimpleRabbitListenerEndpoint.class);
        SimpleRabbitListenerEndpoint endpoint = (SimpleRabbitListenerEndpoint) captor.getValue();
        assertThat(endpoint.getId()).isEqualTo("testWorkerListener");
        assertThat(endpoint.getQueueNames()).containsExactly("ph.swarm.generator");

        Message message = new Message("payload".getBytes(), new MessageProperties());
        endpoint.getMessageListener().onMessage(message);
        verify(delegateListener).onMessage(message);
    }

    private static final class TestRabbitWorkInput implements WorkInput, MessageListener {

        private final MessageListener delegate;

        private TestRabbitWorkInput(MessageListener delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onMessage(Message message) {
            delegate.onMessage(message);
        }
    }

    private static final class TestWorker {
    }

    private static final class TestConfig {
    }
}
