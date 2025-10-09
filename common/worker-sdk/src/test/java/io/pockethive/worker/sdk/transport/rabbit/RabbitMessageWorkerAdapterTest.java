package io.pockethive.worker.sdk.transport.rabbit;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.config.WorkerType;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.context.event.ContextRefreshedEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RabbitMessageWorkerAdapterTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(RabbitMessageWorkerAdapterTest.class);

    @Mock
    private WorkerControlPlaneRuntime controlPlaneRuntime;

    @Mock
    private RabbitListenerEndpointRegistry listenerRegistry;

    @Mock
    private MessageListenerContainer listenerContainer;

    @Mock
    private RabbitMessageWorkerAdapter.WorkDispatcher dispatcher;

    @Mock
    private RabbitMessageWorkerAdapter.MessageResultPublisher resultPublisher;

    @Mock
    private Consumer<Exception> errorHandler;

    private WorkerDefinition workerDefinition;
    private ControlPlaneIdentity identity;
    private Supplier<Boolean> defaultEnabled;
    private Function<WorkerControlPlaneRuntime.WorkerStateSnapshot, Boolean> desiredStateResolver;

    @BeforeEach
    void setUp() {
        workerDefinition = new WorkerDefinition(
            "processorWorker",
            Object.class,
            WorkerType.MESSAGE,
            "processor",
            "processor.in",
            "processor.out",
            Object.class
        );
        identity = new ControlPlaneIdentity("swarm-1", "processor", "instance-1");
        defaultEnabled = () -> true;
        desiredStateResolver = snapshot -> snapshot.enabled().orElseGet(defaultEnabled);
    }

    @Test
    void initialiseStateListenerRegistersControlPlaneHookAndAppliesDefault() {
        when(listenerRegistry.getListenerContainer("listener")).thenReturn(listenerContainer);
        when(listenerContainer.isRunning()).thenReturn(false);

        RabbitMessageWorkerAdapter adapter = builder().build();

        adapter.initialiseStateListener();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<WorkerControlPlaneRuntime.WorkerStateSnapshot>> listenerCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(controlPlaneRuntime).registerStateListener(eq("processorWorker"), listenerCaptor.capture());
        verify(listenerContainer).start();
        verify(controlPlaneRuntime).emitStatusSnapshot();

        WorkerControlPlaneRuntime.WorkerStateSnapshot snapshot = mock(WorkerControlPlaneRuntime.WorkerStateSnapshot.class);
        when(snapshot.enabled()).thenReturn(Optional.of(false));
        when(listenerContainer.isRunning()).thenReturn(true);

        listenerCaptor.getValue().accept(snapshot);

        verify(listenerContainer).stop();
    }

    @Test
    void onWorkDispatchesAndPublishesMessageResults() throws Exception {
        RabbitMessageWorkerAdapter adapter = builder().build();
        RabbitWorkMessageConverter converter = new RabbitWorkMessageConverter();
        Message inbound = converter.toMessage(WorkMessage.text("payload").build());

        when(dispatcher.dispatch(any(WorkMessage.class)))
            .thenReturn(WorkResult.message(WorkMessage.text("processed").build()));

        adapter.onWork(inbound);

        ArgumentCaptor<WorkMessage> workCaptor = ArgumentCaptor.forClass(WorkMessage.class);
        verify(dispatcher).dispatch(workCaptor.capture());
        assertThat(workCaptor.getValue().body()).isEqualTo("payload".getBytes(StandardCharsets.UTF_8));

        ArgumentCaptor<Message> outboundCaptor = ArgumentCaptor.forClass(Message.class);
        verify(resultPublisher).publish(any(WorkResult.Message.class), outboundCaptor.capture());
        assertThat(outboundCaptor.getValue().getBody()).isEqualTo("processed".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void onWorkErrorsDelegateToErrorHandler() throws Exception {
        RabbitMessageWorkerAdapter adapter = builder().build();
        RabbitWorkMessageConverter converter = new RabbitWorkMessageConverter();
        Message inbound = converter.toMessage(WorkMessage.text("payload").build());
        RuntimeException failure = new RuntimeException("boom");
        doThrow(failure).when(dispatcher).dispatch(any(WorkMessage.class));

        adapter.onWork(inbound);

        verify(errorHandler).accept(failure);
        verify(resultPublisher, never()).publish(any(), any());
    }

    @Test
    void onControlValidatesPayloadAndDelegates() {
        RabbitMessageWorkerAdapter adapter = builder().build();
        when(controlPlaneRuntime.handle("{}", "processor.control")).thenReturn(true);

        adapter.onControl("{}", "processor.control", null);

        verify(controlPlaneRuntime).handle("{}", "processor.control");

        assertThatThrownBy(() -> adapter.onControl(" ", "processor.control", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("payload");
        assertThatThrownBy(() -> adapter.onControl("{}", " ", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("routing");
    }

    @Test
    void onApplicationEventReappliesListenerState() {
        RabbitMessageWorkerAdapter adapter = builder().build();
        when(listenerContainer.isRunning()).thenReturn(false);

        adapter.initialiseStateListener();

        reset(listenerRegistry, listenerContainer);
        when(listenerRegistry.getListenerContainer("listener")).thenReturn(listenerContainer);
        when(listenerContainer.isRunning()).thenReturn(true);

        adapter.onApplicationEvent(mock(ContextRefreshedEvent.class));

        verify(listenerRegistry).getListenerContainer("listener");
    }

    private RabbitMessageWorkerAdapter.Builder builder() {
        return RabbitMessageWorkerAdapter.builder()
            .logger(LOGGER)
            .listenerId("listener")
            .displayName("Processor")
            .workerDefinition(workerDefinition)
            .controlPlaneRuntime(controlPlaneRuntime)
            .listenerRegistry(listenerRegistry)
            .identity(identity)
            .defaultEnabledSupplier(defaultEnabled)
            .desiredStateResolver(desiredStateResolver)
            .dispatcher(dispatcher)
            .messageResultPublisher(resultPublisher)
            .dispatchErrorHandler(errorHandler);
    }
}
