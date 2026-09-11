package io.pockethive.worker.sdk.transport.rabbit;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.observability.ObservabilityContextUtil;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerInfo;
import io.pockethive.worker.sdk.config.WorkInputConfig;
import io.pockethive.worker.sdk.config.WorkOutputConfig;
import io.pockethive.work.api.WorkerCapability;
import io.pockethive.work.config.WorkerInputType;
import io.pockethive.work.config.WorkerOutputType;
import io.pockethive.worker.sdk.runtime.WorkIoBindings;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.pockethive.rabbit.api.RabbitMessage;
import io.pockethive.rabbit.api.RabbitListeners;
import io.pockethive.rabbit.api.RabbitListenerState;
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
    private RabbitListeners listenerRegistry;


    @Mock
    private RabbitWorkDispatcher dispatcher;

    @Mock
    private Consumer<Exception> errorHandler;



    private WorkerDefinition workerDefinition;
    private ControlPlaneIdentity identity;

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
    }

    @Test
    void initialiseStateListenerRegistersControlPlaneHookAndWaitsForEnablement() {
        when(listenerRegistry.state("listener")).thenReturn(RabbitListenerState.STOPPED);

        RabbitMessageWorkerAdapter adapter = builder().build();

        adapter.initialiseStateListener();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<WorkerControlPlaneRuntime.WorkerStateSnapshot>> listenerCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(controlPlaneRuntime).registerStateListener(eq("processorWorker"), listenerCaptor.capture());
        verify(controlPlaneRuntime).emitStatusSnapshot();
        verify(listenerRegistry, never()).start("listener");

        WorkerControlPlaneRuntime.WorkerStateSnapshot disabledSnapshot = mock(WorkerControlPlaneRuntime.WorkerStateSnapshot.class);
        when(disabledSnapshot.enabled()).thenReturn(false);
        listenerCaptor.getValue().accept(disabledSnapshot);
        verify(listenerRegistry, never()).start("listener");
        verify(listenerRegistry, never()).stop("listener");

        WorkerControlPlaneRuntime.WorkerStateSnapshot enabledSnapshot = mock(WorkerControlPlaneRuntime.WorkerStateSnapshot.class);
        when(enabledSnapshot.enabled()).thenReturn(true);
        listenerCaptor.getValue().accept(enabledSnapshot);
        verify(listenerRegistry).start("listener");
        when(listenerRegistry.state("listener")).thenReturn(RabbitListenerState.RUNNING);

        WorkerControlPlaneRuntime.WorkerStateSnapshot snapshotDisabledAgain = mock(WorkerControlPlaneRuntime.WorkerStateSnapshot.class);
        when(snapshotDisabledAgain.enabled()).thenReturn(false);
        listenerCaptor.getValue().accept(snapshotDisabledAgain);
        verify(listenerRegistry).stop("listener");
    }

    @Test
    void onApplicationEventReappliesListenerState() {
        RabbitMessageWorkerAdapter adapter = builder().build();
        when(listenerRegistry.state("listener")).thenReturn(RabbitListenerState.STOPPED);

        adapter.initialiseStateListener();

        reset(listenerRegistry);
        when(listenerRegistry.state("listener")).thenReturn(RabbitListenerState.RUNNING);

        adapter.onApplicationEvent(mock(ContextRefreshedEvent.class));

        verify(listenerRegistry).state("listener");
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

    private RabbitMessageWorkerAdapterBuilder baseBuilder() {
        return RabbitMessageWorkerAdapter.builder()
            .logger(LOGGER)
            .listenerId("listener")
            .displayName("Processor")
            .workerDefinition(workerDefinition)
            .controlPlaneRuntime(controlPlaneRuntime)
            .listenerRegistry(listenerRegistry)
            .identity(identity)
            .desiredStateResolver(WorkerControlPlaneRuntime.WorkerStateSnapshot::enabled)
            .dispatcher(dispatcher)
            .dispatchErrorHandler(errorHandler);
    }

    private RabbitMessageWorkerAdapterBuilder baseBuilderWithoutErrorHandler() {
        return RabbitMessageWorkerAdapter.builder()
            .logger(LOGGER)
            .listenerId("listener")
            .displayName("Processor")
            .workerDefinition(workerDefinition)
            .controlPlaneRuntime(controlPlaneRuntime)
            .listenerRegistry(listenerRegistry)
            .identity(identity)
            .desiredStateResolver(WorkerControlPlaneRuntime.WorkerStateSnapshot::enabled)
            .dispatcher(dispatcher);
    }

    private WorkItem workItem(String payload) {
        WorkerInfo info = new WorkerInfo(
            workerDefinition.role(),
            identity.swarmId(),
            identity.instanceId(),
            null,
            null
        );
        ObservabilityContext observability = ObservabilityContextUtil.init(info.role(), info.instanceId(), info.swarmId());
        return WorkItem.text(info, payload)
            .observabilityContext(observability)
            .build();
    }

    private RabbitMessageWorkerAdapterBuilder builder() {
        return baseBuilder();
    }

    private RabbitMessageWorkerAdapterBuilder builderWithoutTemplate() {
        return baseBuilder();
    }

}
