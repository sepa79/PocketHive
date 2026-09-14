package io.pockethive.worker.sdk.input.message;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.observability.ObservabilityContextUtil;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerInfo;
import io.pockethive.work.config.binding.WorkInputConfig;
import io.pockethive.work.config.binding.WorkOutputConfig;
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
import io.pockethive.work.api.transport.WorkInputChannel;
import io.pockethive.worker.sdk.input.WorkMessageDispatcher;

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
class MessageWorkExecutionTest {
    @Test
    void callbackReturnsWhileAcceptedItemIsStillExecuting() throws Exception {
        var execution = execution(builder());
        execution.setMaxInFlight(2);
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var returned = new java.util.concurrent.CountDownLatch(1);
        var finished = new java.util.concurrent.CountDownLatch(1);
        when(dispatcher.dispatch(any(WorkItem.class))).thenAnswer(call -> {
            started.countDown();
            try {
                release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                return call.getArgument(0);
            } finally {
                finished.countDown();
            }
        });
        var callback = Thread.startVirtualThread(() -> {
            execution.onWork(workItem("payload"));
            returned.countDown();
        });
        try {
            assertThat(started.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(returned.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(finished.getCount()).isEqualTo(1);
        } finally {
            release.countDown();
            callback.join(2000);
        }
        assertThat(finished.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void executorRejectionKeepsHistoricalSynchronousDispatch() throws Exception {
        var execution = execution(builder());
        var executor = mock(ThreadPoolExecutor.class);
        var rejection = new RejectedExecutionException("saturated");
        doThrow(rejection).when(executor).execute(any(Runnable.class));
        Field pool = MessageWorkExecution.class.getDeclaredField("workExecutor");
        pool.setAccessible(true);
        pool.set(execution, executor);
        Field maximum = MessageWorkExecution.class.getDeclaredField("maxInFlight");
        maximum.setAccessible(true);
        ((AtomicInteger) maximum.get(execution)).set(2);
        execution.onWork(workItem("payload"));
        verify(dispatcher).dispatch(any(WorkItem.class));
        verify(errorHandler).accept(rejection);
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(MessageWorkExecutionTest.class);

    @Mock
    private WorkerControlPlaneRuntime controlPlaneRuntime;

    @Mock
    private WorkInputChannel channel;

    @Mock
    private WorkMessageDispatcher dispatcher;

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
    void onWorkDispatchesOnceAndLeavesResultPublicationToRuntime() throws Exception {
        MessageWorkExecution adapter = execution(builder());
        WorkItem inbound = workItem("payload");

        when(dispatcher.dispatch(any(WorkItem.class)))
            .thenReturn(workItem("processed"));

        assertThatCode(() -> adapter.onWork(inbound)).doesNotThrowAnyException();

        ArgumentCaptor<WorkItem> workCaptor = ArgumentCaptor.forClass(WorkItem.class);
        verify(dispatcher).dispatch(workCaptor.capture());
        assertThat(workCaptor.getValue().body()).isEqualTo("payload".getBytes(StandardCharsets.UTF_8));

        verifyNoInteractions(errorHandler);
    }

    @Test
    void onWorkErrorsDelegateToErrorHandler() throws Exception {
        MessageWorkExecution adapter = execution(builder());
        WorkItem inbound = workItem("payload");
        RuntimeException failure = new RuntimeException("boom");
        doThrow(failure).when(dispatcher).dispatch(any(WorkItem.class));

        assertThatCode(() -> adapter.onWork(inbound)).doesNotThrowAnyException();

        verify(errorHandler).accept(failure);
        verify(controlPlaneRuntime).publishWorkError(eq(workerDefinition.beanName()), any(WorkItem.class), eq(failure));
    }

    @Test
    void onWorkSwallowsDispatchErrorHandlerFailureWhenDispatcherThrows() throws Exception {
        MessageWorkExecution adapter = execution(builder());
        WorkItem inbound = workItem("payload");
        RuntimeException dispatchFailure = new RuntimeException("boom");
        RuntimeException handlerFailure = new RuntimeException("handler failed");
        doThrow(dispatchFailure).when(dispatcher).dispatch(any(WorkItem.class));
        doThrow(handlerFailure).when(errorHandler).accept(any(Exception.class));

        assertThatCode(() -> adapter.onWork(inbound)).doesNotThrowAnyException();

        verify(controlPlaneRuntime).publishWorkError(eq(workerDefinition.beanName()), any(WorkItem.class), eq(dispatchFailure));
    }

    @Test
    void onWorkDecoderErrorsPublishAlertAndDelegateToErrorHandler() {
        MessageWorkExecution adapter = execution(builder());
        byte[] inbound = "not-a-json-envelope".getBytes(StandardCharsets.UTF_8);

        assertThatCode(() -> adapter.onDecodeFailure(inbound, new IllegalArgumentException("invalid envelope"))).doesNotThrowAnyException();

        verify(controlPlaneRuntime).publishWorkError(eq(workerDefinition.beanName()), any(WorkItem.class), any(Throwable.class));
        verify(errorHandler).accept(any(Exception.class));
    }

    @Test
    void onWorkSwallowsDispatchErrorHandlerFailureWhenDecodeFails() {
        MessageWorkExecution adapter = execution(builder());
        byte[] inbound = "not-a-json-envelope".getBytes(StandardCharsets.UTF_8);
        RuntimeException handlerFailure = new RuntimeException("handler failed");
        doThrow(handlerFailure).when(errorHandler).accept(any(Exception.class));

        assertThatCode(() -> adapter.onDecodeFailure(inbound, new IllegalArgumentException("invalid envelope"))).doesNotThrowAnyException();

        verify(controlPlaneRuntime).publishWorkError(eq(workerDefinition.beanName()), any(WorkItem.class), any(Throwable.class));
    }

    @Test
    void onWorkPublishesAlertWhenDispatcherThrowsAndNoCustomHandlerConfigured() throws Exception {
        MessageWorkExecution adapter = execution(baseBuilderWithoutErrorHandler());
        WorkItem inbound = workItem("payload").toBuilder().messageId("mid-1").build();
        RuntimeException failure = new RuntimeException("boom");
        doThrow(failure).when(dispatcher).dispatch(any(WorkItem.class));

        assertThatCode(() -> adapter.onWork(inbound)).doesNotThrowAnyException();

        verify(controlPlaneRuntime).publishWorkError(eq(workerDefinition.beanName()), any(WorkItem.class), eq(failure));
    }

    private MessageWorkInputBuilder baseBuilder() {
        return MessageWorkInput.builder()
            .logger(LOGGER)
                        .displayName("Processor")
            .workerDefinition(workerDefinition)
            .controlPlaneRuntime(controlPlaneRuntime)
            .channel(channel)
            .identity(identity)
            .dispatcher(dispatcher)
            .dispatchErrorHandler(errorHandler);
    }

    private MessageWorkInputBuilder baseBuilderWithoutErrorHandler() {
        return MessageWorkInput.builder()
            .logger(LOGGER)
                        .displayName("Processor")
            .workerDefinition(workerDefinition)
            .controlPlaneRuntime(controlPlaneRuntime)
            .channel(channel)
            .identity(identity)
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

    private MessageWorkInputBuilder builder() {
        return baseBuilder();
    }

    private MessageWorkInputBuilder builderWithoutTemplate() {
        return baseBuilder();
    }

    private MessageWorkExecution execution(MessageWorkInputBuilder builder) {
        builder.build(); // Apply the same builder validation/default error reporter as production.
        return new MessageWorkExecution(builder);
    }
}
