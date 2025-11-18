package io.pockethive.worker.sdk.transport.rabbit;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.config.WorkInputConfig;
import io.pockethive.worker.sdk.config.WorkOutputConfig;
import io.pockethive.worker.sdk.config.WorkerCapability;
import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.config.WorkerOutputType;
import io.pockethive.worker.sdk.runtime.WorkIoBindings;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import java.nio.charset.StandardCharsets;
import java.util.Set;
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
            WorkerInputType.RABBITMQ,
            "processor",
            WorkIoBindings.of("processor.in", "processor.out", "ph.test.hive"),
            Object.class,
            WorkInputConfig.class,
            WorkOutputConfig.class,
            WorkerOutputType.RABBITMQ,
            "Processor worker",
            Set.of(WorkerCapability.MESSAGE_DRIVEN)
        );
        identity = new ControlPlaneIdentity("swarm-1", "processor", "instance-1");
        defaults = new DummyConfig();
    }

    @Test
    void initialiseStateListenerRegistersControlPlaneHookAndWaitsForEnablement() {
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
        verify(listenerContainer, never()).start();

        WorkerControlPlaneRuntime.WorkerStateSnapshot disabledSnapshot = mock(WorkerControlPlaneRuntime.WorkerStateSnapshot.class);
        when(disabledSnapshot.enabled()).thenReturn(false);
        listenerCaptor.getValue().accept(disabledSnapshot);
        verify(listenerContainer, never()).start();
        verify(listenerContainer, never()).stop();

        WorkerControlPlaneRuntime.WorkerStateSnapshot enabledSnapshot = mock(WorkerControlPlaneRuntime.WorkerStateSnapshot.class);
        when(enabledSnapshot.enabled()).thenReturn(true);
        listenerCaptor.getValue().accept(enabledSnapshot);
        verify(listenerContainer).start();
        when(listenerContainer.isRunning()).thenReturn(true);

        WorkerControlPlaneRuntime.WorkerStateSnapshot snapshotDisabledAgain = mock(WorkerControlPlaneRuntime.WorkerStateSnapshot.class);
        when(snapshotDisabledAgain.enabled()).thenReturn(false);
        listenerCaptor.getValue().accept(snapshotDisabledAgain);
        verify(listenerContainer).stop();
    }

    @Test
    void onWorkDispatchesAndPublishesMessageResults() throws Exception {
        RabbitMessageWorkerAdapter adapter = builder().build();
        RabbitWorkItemConverter converter = new RabbitWorkItemConverter();
        Message inbound = converter.toMessage(WorkItem.text("payload").build());

        when(dispatcher.dispatch(any(WorkItem.class)))
            .thenReturn(WorkItem.text("processed").build());

        adapter.onWork(inbound);

        ArgumentCaptor<WorkItem> workCaptor = ArgumentCaptor.forClass(WorkItem.class);
        verify(dispatcher).dispatch(workCaptor.capture());
        assertThat(workCaptor.getValue().body()).isEqualTo("payload".getBytes(StandardCharsets.UTF_8));

        ArgumentCaptor<Message> outboundCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate)
            .send(eq(workerDefinition.io().outboundExchange()), Mockito.<String>eq(workerDefinition.io().outboundQueue()), outboundCaptor.capture());
        assertThat(outboundCaptor.getValue().getBody()).isEqualTo("processed".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void onWorkErrorsDelegateToErrorHandler() throws Exception {
        RabbitMessageWorkerAdapter adapter = builder().build();
        RabbitWorkItemConverter converter = new RabbitWorkItemConverter();
        Message inbound = converter.toMessage(WorkItem.text("payload").build());
        RuntimeException failure = new RuntimeException("boom");
        doThrow(failure).when(dispatcher).dispatch(any(WorkItem.class));

        adapter.onWork(inbound);

        verify(errorHandler).accept(failure);
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void onWorkUsesCustomPublisherWhenProvided() throws Exception {
        RabbitMessageWorkerAdapter adapter = builderWithoutTemplate()
            .messageResultPublisher(resultPublisher)
            .build();
        RabbitWorkItemConverter converter = new RabbitWorkItemConverter();
        Message inbound = converter.toMessage(WorkItem.text("payload").build());

        when(dispatcher.dispatch(any(WorkItem.class)))
            .thenReturn(WorkItem.text("processed").build());

        adapter.onWork(inbound);

        verify(resultPublisher).publish(any(WorkItem.class), any(Message.class));
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
            WorkerInputType.RABBITMQ,
            "processor",
            WorkIoBindings.of("processor.in", null, "ph.test.hive"),
            Object.class,
            WorkInputConfig.class,
            WorkOutputConfig.class,
            WorkerOutputType.RABBITMQ,
            "Processor worker",
            Set.of(WorkerCapability.MESSAGE_DRIVEN)
        );

        assertThatThrownBy(() -> builder().build())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("outbound queue");
    }

    @Test
    void buildFailsWhenTemplateConfiguredWithoutExchange() {
        workerDefinition = new WorkerDefinition(
            "processorWorker",
            Object.class,
            WorkerInputType.RABBITMQ,
            "processor",
            WorkIoBindings.of("processor.in", "processor.out", null),
            Object.class,
            WorkInputConfig.class,
            WorkOutputConfig.class,
            WorkerOutputType.RABBITMQ,
            "Processor worker",
            Set.of(WorkerCapability.MESSAGE_DRIVEN)
        );

        assertThatThrownBy(() -> builder().build())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("exchange");
    }

    @Test
    void buildAllowsMissingOutboundQueueWhenNoPublisherConfigured() {
        workerDefinition = new WorkerDefinition(
            "processorWorker",
            Object.class,
            WorkerInputType.RABBITMQ,
            "processor",
            WorkIoBindings.of("processor.in", null, "ph.test.hive"),
            Object.class,
            WorkInputConfig.class,
            WorkOutputConfig.class,
            WorkerOutputType.RABBITMQ,
            "Processor worker",
            Set.of(WorkerCapability.MESSAGE_DRIVEN)
        );

        assertThatCode(() -> builderWithoutTemplate().build()).doesNotThrowAnyException();
    }

    @Test
    void onWorkThrowsWhenMessageResultProducedWithoutOutboundQueue() throws Exception {
        workerDefinition = new WorkerDefinition(
            "processorWorker",
            Object.class,
            WorkerInputType.RABBITMQ,
            "processor",
            WorkIoBindings.of("processor.in", null, null),
            Object.class,
            WorkInputConfig.class,
            WorkOutputConfig.class,
            WorkerOutputType.RABBITMQ,
            "Processor worker",
            Set.of(WorkerCapability.MESSAGE_DRIVEN)
        );

        RabbitMessageWorkerAdapter adapter = builderWithoutTemplate().build();
        RabbitWorkItemConverter converter = new RabbitWorkItemConverter();
        Message inbound = converter.toMessage(WorkItem.text("payload").build());

        when(dispatcher.dispatch(any(WorkItem.class)))
            .thenReturn(WorkItem.text("processed").build());

        adapter.onWork(inbound);

        verify(errorHandler).accept(any(Exception.class));
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void onWorkThrowsWhenMessageResultProducedWithoutPublisher() throws Exception {
        workerDefinition = new WorkerDefinition(
            "processorWorker",
            Object.class,
            WorkerInputType.RABBITMQ,
            "processor",
            WorkIoBindings.of("processor.in", "processor.out", "ph.test.hive"),
            Object.class,
            WorkInputConfig.class,
            WorkOutputConfig.class,
            WorkerOutputType.RABBITMQ,
            "Processor worker",
            Set.of(WorkerCapability.MESSAGE_DRIVEN)
        );

        RabbitMessageWorkerAdapter adapter = builderWithoutTemplate().build();
        RabbitWorkItemConverter converter = new RabbitWorkItemConverter();
        Message inbound = converter.toMessage(WorkItem.text("payload").build());

        when(dispatcher.dispatch(any(WorkItem.class)))
            .thenReturn(WorkItem.text("processed").build());

        adapter.onWork(inbound);

        verify(errorHandler).accept(any(Exception.class));
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
            .defaultEnabledSupplier(() -> false)
            .defaultConfigSupplier(() -> defaults)
            .desiredStateResolver(WorkerControlPlaneRuntime.WorkerStateSnapshot::enabled)
            .dispatcher(dispatcher)
            .dispatchErrorHandler(errorHandler);
    }

    private RabbitMessageWorkerAdapter.Builder builder() {
        return baseBuilder().rabbitTemplate(rabbitTemplate);
    }

    private RabbitMessageWorkerAdapter.Builder builderWithoutTemplate() {
        return baseBuilder();
    }

    private record DummyConfig() {
    }
}
