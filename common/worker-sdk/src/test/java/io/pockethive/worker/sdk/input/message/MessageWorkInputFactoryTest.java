package io.pockethive.worker.sdk.input.message;

import io.pockethive.rabbit.work.RabbitWorkInputFactory;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.rabbit.api.RabbitListeners;
import io.pockethive.rabbit.api.RabbitMessage;
import io.pockethive.rabbit.api.RabbitSubscription;
import io.pockethive.rabbit.work.RabbitInputProperties;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRuntime;
import io.pockethive.work.api.WorkItemJsonCodec;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerInfo;
import io.pockethive.work.config.WorkConfigurationException;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MessageWorkInputFactoryTest {
    private final RabbitListeners listeners = mock(RabbitListeners.class);
    private final WorkerRuntime runtime = mock(WorkerRuntime.class);
    private final WorkerDefinition definition = mock(WorkerDefinition.class);
    private final MessageWorkInputFactory factory = new MessageWorkInputFactory(runtime,
        mock(WorkerControlPlaneRuntime.class), new ControlPlaneIdentity("swarm", "processor", "instance"), new RabbitWorkInputFactory(listeners));

    @Test
    void rejectsInvalidSettingsBeforeRegisteringOrDispatching() {
        RabbitInputProperties properties = new RabbitInputProperties();
        properties.setQueue("jobs");
        properties.setPrefetch(0);
        assertThatThrownBy(() -> factory.create(definition, properties)).isInstanceOf(WorkConfigurationException.class);
        verifyNoInteractions(listeners, runtime);
    }

    @Test
    void rejectsExclusiveConcurrencyBeforeCreatingSubscription() {
        RabbitInputProperties properties = new RabbitInputProperties();
        properties.setQueue("jobs");
        properties.setConcurrentConsumers(2);
        properties.setExclusive(true);
        assertThatThrownBy(() -> factory.create(definition, properties))
            .isInstanceOf(WorkConfigurationException.class).hasMessageContaining("concurrentConsumers=1");
        verifyNoInteractions(listeners, runtime);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void passesSelectedSettingsAndDispatchesReceivedEnvelopeOnce() throws Exception {
        when(definition.beanName()).thenReturn("processor");
        when(definition.beanType()).thenReturn((Class) Object.class);
        RabbitInputProperties properties = new RabbitInputProperties();
        properties.setQueue(" jobs ");
        properties.setPrefetch(17);
        properties.setConcurrentConsumers(3);
        properties.setExclusive(false);
        var input = (MessageWorkInput) factory.create(definition, properties);
        when(listeners.state("processorListener")).thenReturn(io.pockethive.rabbit.api.RabbitListenerState.STOPPED);
        input.startListener();
        ArgumentCaptor<Consumer<RabbitMessage>> callback = ArgumentCaptor.forClass(Consumer.class);
        verify(listeners).register(eq(new RabbitSubscription("processorListener", "jobs", 17, 3, false, false)), callback.capture());
        WorkItem item = WorkItem.text(new WorkerInfo("processor", "swarm", "instance", null, null), "payload")
            .observabilityContext(io.pockethive.observability.ObservabilityContextUtil.init("processor", "instance", "swarm")).build();
        callback.getValue().accept(RabbitMessage.json(new WorkItemJsonCodec().toJson(item), true));
        ArgumentCaptor<WorkItem> received = ArgumentCaptor.forClass(WorkItem.class);
        verify(runtime, timeout(2000)).dispatch(eq("processor"), received.capture());
        assertThat(received.getValue().asString()).isEqualTo("payload");
        verifyNoMoreInteractions(runtime);
        input.close();
    }
}
