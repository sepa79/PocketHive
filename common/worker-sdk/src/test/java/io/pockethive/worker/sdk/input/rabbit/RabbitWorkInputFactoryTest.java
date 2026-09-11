package io.pockethive.worker.sdk.input.rabbit;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.rabbit.api.RabbitListeners;
import io.pockethive.rabbit.api.RabbitMessage;
import io.pockethive.rabbit.api.RabbitSubscription;
import io.pockethive.worker.sdk.config.RabbitInputProperties;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRuntime;
import io.pockethive.worker.sdk.transport.rabbit.RabbitWorkItemConverter;
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

class RabbitWorkInputFactoryTest {
    private final RabbitListeners listeners = mock(RabbitListeners.class);
    private final WorkerRuntime runtime = mock(WorkerRuntime.class);
    private final WorkerDefinition definition = mock(WorkerDefinition.class);
    private final RabbitWorkInputFactory factory = new RabbitWorkInputFactory(runtime,
        mock(WorkerControlPlaneRuntime.class), new ControlPlaneIdentity("swarm", "processor", "instance"), listeners);

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
        factory.create(definition, properties);
        ArgumentCaptor<Consumer<RabbitMessage>> callback = ArgumentCaptor.forClass(Consumer.class);
        verify(listeners).register(eq(new RabbitSubscription("processorListener", "jobs", 17, 3, false, false)), callback.capture());
        WorkItem item = WorkItem.text(new WorkerInfo("processor", "swarm", "instance", null, null), "payload")
            .observabilityContext(io.pockethive.observability.ObservabilityContextUtil.init("processor", "instance", "swarm")).build();
        callback.getValue().accept(new RabbitWorkItemConverter().toMessage(item));
        ArgumentCaptor<WorkItem> received = ArgumentCaptor.forClass(WorkItem.class);
        verify(runtime).dispatch(eq("processor"), received.capture());
        assertThat(received.getValue().asString()).isEqualTo("payload");
        verifyNoMoreInteractions(runtime);
    }
}
