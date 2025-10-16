package io.pockethive.worker.sdk.transport.rabbit;

import io.pockethive.Topology;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.config.WorkerType;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.context.event.ContextRefreshedEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    private Consumer<Exception> errorHandler;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private RabbitMessageWorkerAdapter.MessageResultPublisher resultPublisher;

    private WorkerDefinition workerDefinition;
    private ControlPlaneIdentity identity;
    private DummyConfig defaults;

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
        defaults = new DummyConfig(true);
    }

    @Test
    void initialiseStateListenerRegistersControlPlaneHookAndAppliesDefault() {
        when(listenerRegistry.getListenerContainer("listener")).thenReturn(listenerContainer);
        when(listenerContainer.isRunning()).thenReturn(false);

        RabbitMessageWorkerAdapter adapter = builder().build();

        adapter.initialiseStateListener();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<WorkerControlPlaneRuntime.WorkerStateSnapshot>> listenerCaptor = ArgumentCaptor.forClass(Consumer.class);
        InOrder inOrder = Mockito.inOrder(controlPlaneRuntime);
        inOrder.verify(controlPlaneRuntime).registerDefaultConfig(eq("processorWorker"), eq(defaults));
        inOrder.verify(controlPlaneRuntime).registerStateListener(eq("processorWorker"), listenerCaptor.capture());
        inOrder.verify(controlPlaneRuntime).emitStatusSnapshot();
        verify(listenerContainer).start();

        WorkerControlPlaneRuntime.WorkerStateSnapshot snapshot = mock(WorkerControlPlaneRuntime.WorkerStateSnapshot.class);
        when(snapshot.enabled()).thenReturn(Optional.empty());
        when(snapshot.config(DummyConfig.class)).thenReturn(Optional.of(new DummyConfig(false)));
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
        verify(rabbitTemplate)
            .send(eq(Topology.EXCHANGE), Mockito.<String>eq(workerDefinition.resolvedOutQueue()), outboundCaptor.capture());
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
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void onWorkThrowsWhenDispatcherReturnsNullResult() throws Exception {
        RabbitMessageWorkerAdapter adapter = builder().build();
        RabbitWorkMessageConverter converter = new RabbitWorkMessageConverter();
        Message inbound = converter.toMessage(WorkMessage.text("payload").build());

        when(dispatcher.dispatch(any(WorkMessage.class))).thenReturn(null);

        adapter.onWork(inbound);

        ArgumentCaptor<Exception> exceptionCaptor = ArgumentCaptor.forClass(Exception.class);
        verify(errorHandler).accept(exceptionCaptor.capture());
        assertThat(exceptionCaptor.getValue())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("null WorkResult");
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void onWorkUsesCustomPublisherWhenProvided() throws Exception {
        RabbitMessageWorkerAdapter adapter = builderWithoutTemplate()
            .messageResultPublisher(resultPublisher)
            .build();
        RabbitWorkMessageConverter converter = new RabbitWorkMessageConverter();
        Message inbound = converter.toMessage(WorkMessage.text("payload").build());

        when(dispatcher.dispatch(any(WorkMessage.class)))
            .thenReturn(WorkResult.message(WorkMessage.text("processed").build()));

        adapter.onWork(inbound);

        verify(resultPublisher).publish(any(WorkResult.Message.class), any(Message.class));
        verifyNoInteractions(rabbitTemplate);
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

    @Test
    void buildFailsWhenTemplateConfiguredWithoutOutboundQueue() {
        workerDefinition = new WorkerDefinition(
            "processorWorker",
            Object.class,
            WorkerType.MESSAGE,
            "processor",
            "processor.in",
            null,
            Object.class
        );

        assertThatThrownBy(() -> builder().build())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("outbound queue");
    }

    @Test
    void buildAllowsMissingOutboundQueueWhenNoPublisherConfigured() {
        workerDefinition = new WorkerDefinition(
            "processorWorker",
            Object.class,
            WorkerType.MESSAGE,
            "processor",
            "processor.in",
            null,
            Object.class
        );

        assertThatCode(() -> builderWithoutTemplate().build()).doesNotThrowAnyException();
    }

    @Test
    void onWorkThrowsWhenMessageResultProducedWithoutOutboundQueue() throws Exception {
        workerDefinition = new WorkerDefinition(
            "processorWorker",
            Object.class,
            WorkerType.MESSAGE,
            "processor",
            "processor.in",
            null,
            Object.class
        );

        RabbitMessageWorkerAdapter adapter = builderWithoutTemplate().build();
        RabbitWorkMessageConverter converter = new RabbitWorkMessageConverter();
        Message inbound = converter.toMessage(WorkMessage.text("payload").build());

        when(dispatcher.dispatch(any(WorkMessage.class)))
            .thenReturn(WorkResult.message(WorkMessage.text("processed").build()));

        adapter.onWork(inbound);

        ArgumentCaptor<Exception> exceptionCaptor = ArgumentCaptor.forClass(Exception.class);
        verify(errorHandler).accept(exceptionCaptor.capture());
        assertThat(exceptionCaptor.getValue())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("outbound queue");
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void onWorkThrowsWhenMessageResultProducedWithoutPublisher() throws Exception {
        workerDefinition = new WorkerDefinition(
            "processorWorker",
            Object.class,
            WorkerType.MESSAGE,
            "processor",
            "processor.in",
            "processor.out",
            Object.class
        );

        RabbitMessageWorkerAdapter adapter = builderWithoutTemplate().build();
        RabbitWorkMessageConverter converter = new RabbitWorkMessageConverter();
        Message inbound = converter.toMessage(WorkMessage.text("payload").build());

        when(dispatcher.dispatch(any(WorkMessage.class)))
            .thenReturn(WorkResult.message(WorkMessage.text("processed").build()));

        adapter.onWork(inbound);

        ArgumentCaptor<Exception> exceptionCaptor = ArgumentCaptor.forClass(Exception.class);
        verify(errorHandler).accept(exceptionCaptor.capture());
        assertThat(exceptionCaptor.getValue())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("message result publisher");
        verify(rabbitTemplate, never()).send(anyString(), anyString(), any(Message.class));
    }

    private RabbitMessageWorkerAdapter.Builder baseBuilder() {
        return RabbitMessageWorkerAdapter.builder()
            .logger(LOGGER)
            .listenerId("listener")
            .displayName("Processor")
            .workerDefinition(workerDefinition)
            .controlPlaneRuntime(controlPlaneRuntime)
            .listenerRegistry(listenerRegistry)
            .identity(identity)
            .withConfigDefaults(DummyConfig.class, () -> defaults, DummyConfig::enabled)
            .dispatcher(dispatcher)
            .dispatchErrorHandler(errorHandler);
    }

    private RabbitMessageWorkerAdapter.Builder builder() {
        return baseBuilder().rabbitTemplate(rabbitTemplate);
    }

    private RabbitMessageWorkerAdapter.Builder builderWithoutTemplate() {
        return baseBuilder();
    }

    private record DummyConfig(boolean enabled) {
    }
}
