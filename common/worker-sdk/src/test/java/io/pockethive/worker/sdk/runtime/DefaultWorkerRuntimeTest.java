package io.pockethive.worker.sdk.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.work.api.PocketHiveWorkerFunction;
import io.pockethive.work.api.StatusPublisher;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerContext;
import io.pockethive.work.api.WorkerInfo;
import io.pockethive.work.config.binding.WorkInputConfig;
import io.pockethive.work.config.binding.WorkOutputConfig;
import io.pockethive.work.api.WorkerCapability;
import io.pockethive.work.config.WorkerInputType;
import io.pockethive.work.config.WorkerOutputType;
import io.pockethive.worker.sdk.output.WorkOutputRegistry;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultWorkerRuntimeTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void acceptedMessageRunsAfterStopAndReportsItsOutcome(boolean fails) throws Exception {
        var definition = new WorkerDefinition("accepted", TestWorker.class, WorkerInputType.RABBITMQ, "role",
            WorkIoBindings.of("in", "out", "exchange"), TestConfig.class, WorkInputConfig.class,
            WorkOutputConfig.class, WorkerOutputType.RABBITMQ, "accepted", Set.of(WorkerCapability.MESSAGE_DRIVEN));
        var store = new WorkerStateStore();
        var state = store.getOrCreate(definition);
        state.updateConfig(null, false, true);
        var outputs = mock(WorkOutputRegistry.class);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var failure = new IllegalArgumentException("invalid template");
        PocketHiveWorkerFunction worker = (item, context) -> {
            calls.incrementAndGet();
            if (fails) throw failure;
            return item;
        };
        var runtime = new DefaultWorkerRuntime(new WorkerRegistry(List.of(definition)), type -> worker,
            (def, current, item) -> workerContext(def, current), store, List.of(), outputs);
        var waiting = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var finished = new java.util.concurrent.CountDownLatch(1);
        var channel = mock(io.pockethive.work.api.transport.WorkInputChannel.class);
        var control = mock(WorkerControlPlaneRuntime.class);
        var error = new java.util.concurrent.atomic.AtomicReference<Exception>();
        var input = io.pockethive.worker.sdk.input.message.MessageWorkInput.builder()
            .logger(org.slf4j.LoggerFactory.getLogger(getClass())).displayName("accepted")
            .workerDefinition(definition).controlPlaneRuntime(control).channel(channel)
            .identity(new io.pockethive.controlplane.ControlPlaneIdentity("swarm", "role", "instance"))
            .dispatchErrorHandler(ex -> { error.set(ex); finished.countDown(); })
            .dispatcher(item -> {
                waiting.countDown();
                if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test gate timeout");
                var result = runtime.dispatch(definition.beanName(), item);
                finished.countDown();
                return result;
            }).build();
        var callback = org.mockito.ArgumentCaptor.forClass(io.pockethive.work.api.transport.WorkDeliveryHandler.class);
        input.startListener();
        verify(channel).register(callback.capture());
        var item = WorkItem.text(new WorkerInfo("role", "swarm", "instance", "in", "out"), "payload").build();
        try {
            callback.getValue().onWork(item); // Successful admission allows broker ACK.
            assertThat(waiting.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            state.updateConfig(null, false, false);
            input.stopListener();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> callback.getValue().onWork(item))
                .isInstanceOf(io.pockethive.work.api.transport.WorkNotAcceptedException.class);
            release.countDown();
            assertThat(finished.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(calls.get()).isEqualTo(1);
            if (fails) {
                assertThat(error.get()).isSameAs(failure);
                org.mockito.Mockito.verifyNoInteractions(outputs);
                verify(control, org.mockito.Mockito.timeout(2000)).publishWorkError("accepted", item, failure);
            } else {
                assertThat(error.get()).isNull();
                verify(outputs, org.mockito.Mockito.timeout(2000)).publish(org.mockito.ArgumentMatchers.any(), eq(definition));
            }
        } finally {
            release.countDown();
            input.close();
        }
    }

    @Test
    void publishesMessageResultsThroughOutputRegistry() throws Exception {
        WorkerDefinition definition = new WorkerDefinition(
            "testWorker",
            TestWorker.class,
            WorkerInputType.RABBITMQ,
            "role",
            WorkIoBindings.of("in.queue", "out.queue", "exchange"),
            TestConfig.class,
            WorkInputConfig.class,
            WorkOutputConfig.class,
            WorkerOutputType.RABBITMQ,
            "Test worker",
            Set.of(WorkerCapability.MESSAGE_DRIVEN)
        );
        WorkerRegistry registry = new WorkerRegistry(List.of(definition));
        WorkerStateStore store = new WorkerStateStore();
        WorkerContextFactory contextFactory = (def, state, message) -> workerContext(def, state);
        WorkOutputRegistry outputRegistry = mock(WorkOutputRegistry.class);
        DefaultWorkerRuntime runtime = new DefaultWorkerRuntime(
            registry,
            type -> new TestWorker(),
            contextFactory,
            store,
            List.of(),
            outputRegistry
        );
        WorkerState state = store.getOrCreate(definition);
        state.setStatusPublisher(new WorkerStatusPublisher(state, () -> { }, () -> { }));
        state.updateConfig(null, false, Boolean.TRUE);

        WorkIoBindings io = definition.io();
        WorkerInfo info = new WorkerInfo(definition.role(), "swarm", "instance", io.inboundQueue(), io.outboundQueue());
        WorkItem result = runtime.dispatch("testWorker", WorkItem.text(info, "payload").build());

        assertThat(result).isNotNull();
        verify(outputRegistry).publish(eq(result), eq(definition));
        var failure = new IllegalStateException("publication not confirmed");
        org.mockito.Mockito.doThrow(failure).when(outputRegistry).publish(org.mockito.ArgumentMatchers.any(), eq(definition));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> runtime.dispatch("testWorker", WorkItem.text(info, "payload").build()))
            .isSameAs(failure);
        state.updateConfig(null, false, Boolean.FALSE);
        org.mockito.Mockito.clearInvocations(outputRegistry);
        // A previously admitted dispatch still publishes and reports publication failure after STOP.
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            runtime.dispatch("testWorker", WorkItem.text(info, "payload").build())).isSameAs(failure);
        verify(outputRegistry).publish(org.mockito.ArgumentMatchers.any(), eq(definition));
    }

    @Test
    void eachInvocationPublishesOnlyItsOwnDeliveryIntentAndNoResultMeansNoSend() throws Exception {
        var delayed = new io.pockethive.work.config.WorkDelivery(io.pockethive.work.config.WorkDeliveryMode.DELAYED, 3000);
        var immediate = io.pockethive.work.config.WorkDelivery.IMMEDIATE;
        var first = new WorkerDefinition("first", TestWorker.class, WorkerInputType.RABBITMQ, "first",
            new WorkIoBindings(null, "middle", null, delayed), Void.class, WorkInputConfig.class, WorkOutputConfig.class,
            io.pockethive.artemis.api.ArtemisWorkIoType.ARTEMIS, "delayed producer", Set.of());
        var second = new WorkerDefinition("second", TestWorker.class, WorkerInputType.RABBITMQ, "second",
            WorkIoBindings.of("middle", "end", "work"), Void.class, WorkInputConfig.class, WorkOutputConfig.class,
            WorkerOutputType.RABBITMQ, "immediate successor", Set.of());
        var store = new WorkerStateStore();
        var outputs = new WorkOutputRegistry();
        var published = new java.util.ArrayList<io.pockethive.work.config.WorkDelivery>();
        outputs.register(first, (item, intent) -> published.add(intent));
        outputs.register(second, (item, intent) -> published.add(intent));
        PocketHiveWorkerFunction worker = (item, context) -> {
            if (item.asString().equals("drop")) return null;
            if (item.asString().equals("invalid")) throw new IllegalArgumentException("invalid template");
            return item;
        };
        var runtime = new DefaultWorkerRuntime(new WorkerRegistry(List.of(first, second)), type -> worker,
            (definition, state, message) -> workerContext(definition, state), store, List.of(), outputs);
        store.getOrCreate(first).updateConfig(null, false, true);
        store.getOrCreate(second).updateConfig(null, false, true);
        var info = new WorkerInfo("first", "swarm", "instance", null, null);
        var item = WorkItem.text(info, "payload").observabilityContext(
            io.pockethive.observability.ObservabilityContextUtil.init(info.role(), info.instanceId(), info.swarmId())).build();
        var result = runtime.dispatch("first", item);
        var codec = new io.pockethive.work.api.WorkItemJsonCodec();
        runtime.dispatch("second", codec.fromJson(codec.toJson(result)));
        assertThat(published).containsExactly(delayed, immediate);
        assertThat(runtime.dispatch("first", WorkItem.text(info, "drop").build())).isNull();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> runtime.dispatch("first", WorkItem.text(info, "invalid").build()))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("invalid template");
        assertThat(published).containsExactly(delayed, immediate);
    }

    private static WorkerContext workerContext(WorkerDefinition definition, WorkerState state) {
        WorkIoBindings io = definition.io();
        WorkerInfo info = new WorkerInfo(definition.role(), "swarm", "instance", io.inboundQueue(), io.outboundQueue());
        ObservabilityContext observabilityContext = new ObservabilityContext();
        observabilityContext.setTraceId("trace");
        observabilityContext.setSwarmId(info.swarmId());
        observabilityContext.setHops(new java.util.ArrayList<>());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ObservationRegistry observations = ObservationRegistry.create();
        return new WorkerContext() {
            @Override
            public WorkerInfo info() {
                return info;
            }

            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public <C> C config(Class<C> type) {
                return state.config(type).orElse(null);
            }

            @Override
            public StatusPublisher statusPublisher() {
                return state.statusPublisher();
            }

            @Override
            public org.slf4j.Logger logger() {
                return org.slf4j.LoggerFactory.getLogger("test");
            }

            @Override
            public io.micrometer.core.instrument.MeterRegistry meterRegistry() {
                return registry;
            }

            @Override
            public ObservationRegistry observationRegistry() {
                return observations;
            }

            @Override
            public ObservabilityContext observabilityContext() {
                return observabilityContext;
            }
        };
    }

    private record TestConfig(boolean enabled) {
    }

    private static final class TestWorker implements PocketHiveWorkerFunction {

        @Override
        public WorkItem onMessage(WorkItem in, WorkerContext context) {
            return in;
        }
    }
}
