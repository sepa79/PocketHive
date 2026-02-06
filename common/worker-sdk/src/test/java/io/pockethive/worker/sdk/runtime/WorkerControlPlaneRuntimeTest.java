package io.pockethive.worker.sdk.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.pockethive.control.ControlSignal;
import io.pockethive.control.AlertMessage;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.worker.WorkerControlPlane;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.observability.ObservabilityContextUtil;
import io.pockethive.observability.StatusEnvelopeBuilder;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerInfo;
import io.pockethive.worker.sdk.config.WorkInputConfig;
import io.pockethive.worker.sdk.config.WorkOutputConfig;
import io.pockethive.worker.sdk.config.WorkerCapability;
import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.config.WorkerOutputType;
import io.pockethive.worker.sdk.runtime.WorkIoBindings;
import io.pockethive.worker.sdk.testing.ControlPlaneTestFixtures;
import io.pockethive.worker.sdk.templating.TemplateRenderer;
import io.pockethive.controlplane.spring.WorkerControlPlaneProperties;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class WorkerControlPlaneRuntimeTest {
	
	    private static final ObjectMapper MAPPER = new ObjectMapper()
	        .findAndRegisterModules()
	        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
	    private static final String SWARM_ID = "default";
	    private static final ControlPlaneIdentity IDENTITY = new ControlPlaneIdentity(SWARM_ID, "generator", "inst-1");
	    private static final String ORIGIN = "orchestrator-1";
	    private static final Map<String, Object> RUNTIME_META = Map.of("templateId", "tpl-1", "runId", "run-1");
	    private static final WorkerControlPlaneProperties PROPERTIES =
	        ControlPlaneTestFixtures.workerProperties(SWARM_ID, "generator", "inst-1");
	
	    private WorkerStateStore stateStore;
	    private WorkerDefinition definition;
    private WorkerControlPlane controlPlane;
    private WorkerControlPlaneRuntime runtime;

    @Mock
    private ControlPlaneEmitter emitter;

    @BeforeEach
    void setUp() {
        stateStore = new WorkerStateStore();
        definition = new WorkerDefinition(
            "testWorker",
            TestWorker.class,
            WorkerInputType.SCHEDULER,
            "generator",
            WorkIoBindings.of(null, "out.queue", "traffic.exchange"),
            TestConfig.class,
            WorkInputConfig.class,
            WorkOutputConfig.class,
            WorkerOutputType.RABBITMQ,
            "Test worker",
            Set.of(WorkerCapability.SCHEDULER)
        );
        stateStore.getOrCreate(definition);
        controlPlane = WorkerControlPlane.builder(MAPPER)
            .identity(IDENTITY)
            .build();
        runtime = new WorkerControlPlaneRuntime(controlPlane, stateStore, MAPPER, emitter, IDENTITY,
            PROPERTIES.getControlPlane());
        reset(emitter);
    }

    @Test
    void registerDefaultConfigSeedsState() throws Exception {
        TestConfig defaults = new TestConfig(true, 7.5);

        runtime.registerDefaultConfig(definition.beanName(), defaults);

        assertThat(runtime.workerConfig(definition.beanName(), TestConfig.class)).contains(defaults);
        assertThat(runtime.workerRawConfig(definition.beanName()))
            .containsEntry("ratePerSec", 7.5)
            .containsEntry("enabled", true);
        assertThat(runtime.workerEnabled(definition.beanName())).isFalse();

        reset(emitter);
        runtime.emitStatusSnapshot();

        ArgumentCaptor<ControlPlaneEmitter.StatusContext> captor =
            ArgumentCaptor.forClass(ControlPlaneEmitter.StatusContext.class);
        verify(emitter).emitStatusSnapshot(captor.capture());

	        Map<String, Object> snapshot = buildSnapshot(captor.getValue());
	        @SuppressWarnings("unchecked")
	        Map<String, Object> data = (Map<String, Object>) snapshot.get("data");
	        @SuppressWarnings("unchecked")
	        Map<String, Object> config = (Map<String, Object>) data.get("config");
	        assertThat(config)
	            .containsEntry("ratePerSec", 7.5)
	            .containsEntry("enabled", true);
	    }

    @Test
    void configUpdateCanonicalisesKebabCaseKeys() throws Exception {
        runtime.registerDefaultConfig(definition.beanName(), new TestConfig(false, 7.5));

        Map<String, Object> args = Map.of("rate-per-sec", 10.0);
        ControlSignal signal = ControlSignal.forInstance(
            "config-update",
            IDENTITY.swarmId(),
            IDENTITY.role(),
            IDENTITY.instanceId(),
            ORIGIN,
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            args
        );
        String payload = MAPPER.writeValueAsString(signal);
        String routingKey = ControlPlaneRouting.signal("config-update", IDENTITY.swarmId(), IDENTITY.role(), IDENTITY.instanceId());

        runtime.handle(payload, routingKey);

        Map<String, Object> rawConfig = runtime.workerRawConfig(definition.beanName());
        assertThat(rawConfig)
            .containsEntry("ratePerSec", 10.0)
            .doesNotContainKey("rate-per-sec");
        assertThat(runtime.workerConfig(definition.beanName(), TestConfig.class))
            .contains(new TestConfig(false, 10.0));
    }

    @Test
	    void workerConfigAccessibleAfterUpdate() throws Exception {
	        String correlationId = UUID.randomUUID().toString();
	        String idempotencyKey = UUID.randomUUID().toString();
	        Map<String, Object> args = Map.of(
                "enabled", true,
                "ratePerSec", 12.5
	        );
			        ControlSignal signal = ControlSignal.forInstance(
			            "config-update",
			            IDENTITY.swarmId(),
			            IDENTITY.role(),
			            IDENTITY.instanceId(),
			            ORIGIN,
			            correlationId,
			            idempotencyKey,
			            args
			        );
	        String payload = MAPPER.writeValueAsString(signal);
	        String routingKey = ControlPlaneRouting.signal("config-update", IDENTITY.swarmId(), IDENTITY.role(), IDENTITY.instanceId());

        boolean handled = runtime.handle(payload, routingKey);

        assertThat(handled).isTrue();
        Optional<TestConfig> config = runtime.workerConfig(definition.beanName(), TestConfig.class);
        assertThat(config).contains(new TestConfig(true, 12.5));
        assertThat(runtime.workerEnabled(definition.beanName())).isTrue();
        Map<String, Object> rawConfig = runtime.workerRawConfig(definition.beanName());
        assertThat(rawConfig).containsEntry("ratePerSec", 12.5);
        ArgumentCaptor<ControlPlaneEmitter.ReadyContext> captor = ArgumentCaptor.forClass(ControlPlaneEmitter.ReadyContext.class);
	        verify(emitter).emitReady(captor.capture());
	        assertThat(captor.getValue().signal()).isEqualTo("config-update");
	    }

	    @Test
	    void configUpdateReseedClearsSeededSelectionsWithoutPersistingDirective() throws Exception {
	        TemplateRenderer renderer = mock(TemplateRenderer.class);
	        WorkerControlPlaneRuntime seededRuntime = new WorkerControlPlaneRuntime(
	            controlPlane,
	            stateStore,
	            MAPPER,
	            emitter,
	            IDENTITY,
	            PROPERTIES.getControlPlane(),
	            renderer
	        );

	        String correlationId = UUID.randomUUID().toString();
	        String idempotencyKey = UUID.randomUUID().toString();
	        Map<String, Object> args = Map.of(
	            "templating", Map.of("reseed", true)
	        );
			        ControlSignal signal = ControlSignal.forInstance(
			            "config-update",
			            IDENTITY.swarmId(),
			            IDENTITY.role(),
			            IDENTITY.instanceId(),
			            ORIGIN,
			            correlationId,
			            idempotencyKey,
			            args
			        );
	        String payload = MAPPER.writeValueAsString(signal);
	        String routingKey = ControlPlaneRouting.signal("config-update", IDENTITY.swarmId(), IDENTITY.role(), IDENTITY.instanceId());

	        boolean handled = seededRuntime.handle(payload, routingKey);

	        assertThat(handled).isTrue();
	        verify(renderer).resetSeededSelections();
	        assertThat(seededRuntime.workerRawConfig(definition.beanName())).doesNotContainKey("templating");
	    }

	    @Test
	    void configUpdateValidationFailureEmitsErrorContext() throws Exception {
	        String correlationId = UUID.randomUUID().toString();
	        String idempotencyKey = UUID.randomUUID().toString();
        Map<String, Object> args = Map.of(
            "enabled", true,
            "ratePerSec", "not-a-number"
        );
		        ControlSignal signal = ControlSignal.forInstance(
		            "config-update",
		            IDENTITY.swarmId(),
		            IDENTITY.role(),
		            IDENTITY.instanceId(),
		            ORIGIN,
		            correlationId,
		            idempotencyKey,
		            args
		        );
        String payload = MAPPER.writeValueAsString(signal);
        String routingKey = ControlPlaneRouting.signal("config-update", IDENTITY.swarmId(), IDENTITY.role(), IDENTITY.instanceId());

        boolean handled = runtime.handle(payload, routingKey);

        assertThat(handled).isTrue();
        ArgumentCaptor<ControlPlaneEmitter.ErrorContext> captor = ArgumentCaptor.forClass(ControlPlaneEmitter.ErrorContext.class);
        verify(emitter).emitError(captor.capture());
        ControlPlaneEmitter.ErrorContext ctx = captor.getValue();
        assertThat(ctx.signal()).isEqualTo("config-update");
        assertThat(ctx.correlationId()).isEqualTo(correlationId);
        assertThat(ctx.idempotencyKey()).isEqualTo(idempotencyKey);
        assertThat(ctx.phase()).isEqualTo("apply");
        assertThat(ctx.details()).containsEntry("worker", definition.beanName());
    }

    @Test
    void statusSnapshotIncludesAggregatedIoState() throws Exception {
        runtime.statusPublisher(definition.beanName()).update(status ->
            status.data("ioState", Map.of(
                "work", Map.of(
                    "input", "out-of-data",
                    "output", "ok",
                    "context", Map.of("dataset", "redis:users")
                )
            )));

        runtime.emitStatusSnapshot();

        ArgumentCaptor<ControlPlaneEmitter.StatusContext> captor =
            ArgumentCaptor.forClass(ControlPlaneEmitter.StatusContext.class);
        verify(emitter).emitStatusSnapshot(captor.capture());

        Map<String, Object> snapshot = buildSnapshot(captor.getValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) snapshot.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> ioState = (Map<String, Object>) data.get("ioState");
        @SuppressWarnings("unchecked")
        Map<String, Object> work = (Map<String, Object>) ioState.get("work");
        assertThat(work).containsEntry("input", "out-of-data").containsEntry("output", "ok");
        @SuppressWarnings("unchecked")
        Map<String, Object> ctx = (Map<String, Object>) work.get("context");
        assertThat(ctx).containsEntry("dataset", "redis:users");
    }

    @Test
    void emitsIoOutOfDataAlertOnTransitionOnly() {
        runtime.statusPublisher(definition.beanName()).update(status ->
            status.data("ioState", Map.of("work", Map.of("input", "ok", "output", "ok"))));

        runtime.emitStatusDelta();

        runtime.statusPublisher(definition.beanName()).update(status ->
            status.data("ioState", Map.of(
                "work", Map.of(
                    "input", "out-of-data",
                    "output", "ok",
                    "context", Map.of("dataset", "csv:customers", "logRef", "loki://trace/123")
                )
            )));

        runtime.emitStatusDelta();
        runtime.emitStatusDelta();

        ArgumentCaptor<AlertMessage> alertCaptor = ArgumentCaptor.forClass(AlertMessage.class);
        verify(emitter, times(1)).publishAlert(alertCaptor.capture());
        AlertMessage alert = alertCaptor.getValue();
        assertThat(alert.data().code()).isEqualTo("io.out-of-data");
        assertThat(alert.data().context()).containsEntry("dataset", "csv:customers");
        assertThat(alert.data().logRef()).isEqualTo("loki://trace/123");
    }

    @Test
    void publishWorkErrorEmitsRuntimeExceptionAlertWithContext() {
        ObservabilityContext trace = ObservabilityContextUtil.init("worker", IDENTITY.instanceId(), IDENTITY.swarmId());
        WorkerInfo info = new WorkerInfo("worker", IDENTITY.swarmId(), IDENTITY.instanceId(), null, null);
        WorkItem item = WorkItem.text(info, "payload")
            .messageId("mid-1")
            .header("x-ph-call-id", "redis-auth")
            .observabilityContext(trace)
            .build();
        RuntimeException failure = new RuntimeException("boom");

        runtime.publishWorkError(definition.beanName(), item, failure);

        ArgumentCaptor<AlertMessage> alertCaptor = ArgumentCaptor.forClass(AlertMessage.class);
        verify(emitter).publishAlert(alertCaptor.capture());
        AlertMessage alert = alertCaptor.getValue();
        assertThat(alert.data().code()).isEqualTo("runtime.exception");
        assertThat(alert.data().context())
            .containsEntry("worker", definition.beanName())
            .containsEntry("messageId", "mid-1")
            .containsEntry("callId", "redis-auth")
            .containsEntry("traceId", trace.getTraceId());
    }

    @Test
	    void configUpdateWithoutEnabledPreservesExistingState() throws Exception {
	        Map<String, Object> initialArgs = Map.of("enabled", true);
			        ControlSignal initialSignal = ControlSignal.forInstance(
		            "config-update",
			            IDENTITY.swarmId(),
			            IDENTITY.role(),
			            IDENTITY.instanceId(),
			            ORIGIN,
			            UUID.randomUUID().toString(),
			            UUID.randomUUID().toString(),
			            initialArgs
			        );
	        String initialPayload = MAPPER.writeValueAsString(initialSignal);
	        String routingKey = ControlPlaneRouting.signal("config-update", IDENTITY.swarmId(), IDENTITY.role(), IDENTITY.instanceId());

        runtime.handle(initialPayload, routingKey);

        assertThat(runtime.workerEnabled(definition.beanName())).isTrue();

	        Map<String, Object> updateArgs = Map.of("ratePerSec", 20.0);
			        ControlSignal updateSignal = ControlSignal.forInstance(
		            "config-update",
			            IDENTITY.swarmId(),
			            IDENTITY.role(),
			            IDENTITY.instanceId(),
			            ORIGIN,
			            UUID.randomUUID().toString(),
			            UUID.randomUUID().toString(),
			            updateArgs
			        );
	        String updatePayload = MAPPER.writeValueAsString(updateSignal);

        runtime.handle(updatePayload, routingKey);

        assertThat(runtime.workerEnabled(definition.beanName())).isTrue();
        Map<String, Object> rawConfig = runtime.workerRawConfig(definition.beanName());
        assertThat(rawConfig).containsEntry("ratePerSec", 20.0);
    }

    @Test
	    void explicitEmptyPayloadResetsWorkerConfig() throws Exception {
	        Map<String, Object> initialArgs = Map.of(
	            "enabled", true,
                "ratePerSec", 15.0
	        );
			        ControlSignal initialSignal = ControlSignal.forInstance(
			            "config-update",
			            IDENTITY.swarmId(),
			            IDENTITY.role(),
			            IDENTITY.instanceId(),
			            ORIGIN,
			            UUID.randomUUID().toString(),
			            UUID.randomUUID().toString(),
			            initialArgs
			        );
	        String routingKey = ControlPlaneRouting.signal("config-update", IDENTITY.swarmId(), IDENTITY.role(), IDENTITY.instanceId());
	        runtime.handle(MAPPER.writeValueAsString(initialSignal), routingKey);

        Map<String, Object> resetArgs = Map.of(
            "workers", Map.of(definition.beanName(), Map.of())
	        );
			        ControlSignal resetSignal = ControlSignal.forInstance(
			            "config-update",
			            IDENTITY.swarmId(),
			            IDENTITY.role(),
			            IDENTITY.instanceId(),
			            ORIGIN,
			            UUID.randomUUID().toString(),
			            UUID.randomUUID().toString(),
			            resetArgs
			        );

        runtime.handle(MAPPER.writeValueAsString(resetSignal), routingKey);

        assertThat(runtime.workerConfig(definition.beanName(), TestConfig.class)).isEmpty();
        assertThat(runtime.workerRawConfig(definition.beanName())).isEmpty();
    }

    @Test
	    void partiallyTargetedWorkersMapDoesNotResetUntouchedWorkers() throws Exception {
        WorkerDefinition otherDefinition = new WorkerDefinition(
            "secondaryWorker",
            TestWorker.class,
            WorkerInputType.SCHEDULER,
            "generator",
            WorkIoBindings.of(null, "out.queue", "traffic.exchange"),
            TestConfig.class,
            WorkInputConfig.class,
            WorkOutputConfig.class,
            WorkerOutputType.RABBITMQ,
            "Test worker",
            Set.of(WorkerCapability.SCHEDULER)
        );
        stateStore.getOrCreate(otherDefinition);
        String routingKey = ControlPlaneRouting.signal("config-update", IDENTITY.swarmId(), IDENTITY.role(), IDENTITY.instanceId());

        Map<String, Object> firstWorkerArgs = Map.of(
            "worker", definition.beanName(),
            "ratePerSec", 17.5
	        );
			        ControlSignal firstWorkerSignal = ControlSignal.forInstance(
			            "config-update",
			            IDENTITY.swarmId(),
			            IDENTITY.role(),
			            IDENTITY.instanceId(),
			            ORIGIN,
			            UUID.randomUUID().toString(),
			            UUID.randomUUID().toString(),
			            firstWorkerArgs
			        );
	        runtime.handle(MAPPER.writeValueAsString(firstWorkerSignal), routingKey);

        Map<String, Object> secondWorkerArgs = Map.of(
            "worker", otherDefinition.beanName(),
            "ratePerSec", 42.0
	        );
			        ControlSignal secondWorkerSignal = ControlSignal.forInstance(
			            "config-update",
			            IDENTITY.swarmId(),
			            IDENTITY.role(),
			            IDENTITY.instanceId(),
			            ORIGIN,
			            UUID.randomUUID().toString(),
			            UUID.randomUUID().toString(),
			            secondWorkerArgs
			        );
	        runtime.handle(MAPPER.writeValueAsString(secondWorkerSignal), routingKey);

        Map<String, Object> broadcastArgs = Map.of(
            "workers", Map.of(
                definition.beanName(), Map.of("ratePerSec", 99.0)
            )
	        );
			        ControlSignal broadcastSignal = ControlSignal.forInstance(
			            "config-update",
			            IDENTITY.swarmId(),
			            IDENTITY.role(),
			            IDENTITY.instanceId(),
			            ORIGIN,
			            UUID.randomUUID().toString(),
			            UUID.randomUUID().toString(),
			            broadcastArgs
			        );
	        runtime.handle(MAPPER.writeValueAsString(broadcastSignal), routingKey);

        assertThat(runtime.workerRawConfig(definition.beanName()))
            .containsEntry("ratePerSec", 99.0);
        assertThat(runtime.workerRawConfig(otherDefinition.beanName()))
            .containsEntry("ratePerSec", 42.0);
        assertThat(runtime.workerConfig(otherDefinition.beanName(), TestConfig.class))
            .contains(new TestConfig(false, 42.0));
    }

    @Test
	    void configUpdateWithoutPayloadDoesNotClearExistingOverride() throws Exception {
        Map<String, Object> initialArgs = Map.of(
            "ratePerSec", 18.0
	        );
			        ControlSignal initialSignal = ControlSignal.forInstance(
			            "config-update",
			            IDENTITY.swarmId(),
			            IDENTITY.role(),
			            IDENTITY.instanceId(),
			            ORIGIN,
			            UUID.randomUUID().toString(),
			            UUID.randomUUID().toString(),
			            initialArgs
			        );
        String routingKey = ControlPlaneRouting.signal("config-update", IDENTITY.swarmId(), IDENTITY.role(), IDENTITY.instanceId());
        runtime.handle(MAPPER.writeValueAsString(initialSignal), routingKey);

        Map<String, Object> noopArgs = Map.of(
            "worker", definition.beanName()
	        );
			        ControlSignal noopSignal = ControlSignal.forInstance(
			            "config-update",
			            IDENTITY.swarmId(),
			            IDENTITY.role(),
			            IDENTITY.instanceId(),
			            ORIGIN,
			            UUID.randomUUID().toString(),
			            UUID.randomUUID().toString(),
			            noopArgs
			        );

        runtime.handle(MAPPER.writeValueAsString(noopSignal), routingKey);

        Map<String, Object> rawConfig = runtime.workerRawConfig(definition.beanName());
        assertThat(rawConfig)
            .containsEntry("ratePerSec", 18.0);
        assertThat(runtime.workerConfig(definition.beanName(), TestConfig.class))
            .contains(new TestConfig(false, 18.0));
    }

    @Test
	    void enablementToggleWithoutConfigRetainsExistingOverrides() throws Exception {
        Map<String, Object> initialArgs = Map.of(
            "enabled", true,
            "ratePerSec", 9.5
	        );
			        ControlSignal initialSignal = ControlSignal.forInstance(
			            "config-update",
			            IDENTITY.swarmId(),
			            IDENTITY.role(),
			            IDENTITY.instanceId(),
			            ORIGIN,
			            UUID.randomUUID().toString(),
			            UUID.randomUUID().toString(),
			            initialArgs
			        );
        String routingKey = ControlPlaneRouting.signal("config-update", IDENTITY.swarmId(), IDENTITY.role(), IDENTITY.instanceId());
        runtime.handle(MAPPER.writeValueAsString(initialSignal), routingKey);

        Map<String, Object> toggleArgs = Map.of(
            "enabled", false
	        );
			        ControlSignal toggleSignal = ControlSignal.forInstance(
			            "config-update",
			            IDENTITY.swarmId(),
			            IDENTITY.role(),
			            IDENTITY.instanceId(),
			            ORIGIN,
			            UUID.randomUUID().toString(),
			            UUID.randomUUID().toString(),
			            toggleArgs
			        );

        runtime.handle(MAPPER.writeValueAsString(toggleSignal), routingKey);

        assertThat(runtime.workerEnabled(definition.beanName())).isFalse();
        Map<String, Object> rawConfig = runtime.workerRawConfig(definition.beanName());
        assertThat(rawConfig)
            .containsEntry("ratePerSec", 9.5)
            .containsEntry("enabled", false);
        assertThat(runtime.workerConfig(definition.beanName(), TestConfig.class))
            .contains(new TestConfig(false, 9.5));
    }

    @Test
	    void partialConfigUpdateRetainsSeededDefaults() throws Exception {
        TestConfig defaults = new TestConfig(true, 7.5);
        runtime.registerDefaultConfig(definition.beanName(), defaults);
        reset(emitter);

        Map<String, Object> args = Map.of(
            "ratePerSec", 20.0
	        );
			        ControlSignal signal = ControlSignal.forInstance(
			            "config-update",
			            IDENTITY.swarmId(),
			            IDENTITY.role(),
			            IDENTITY.instanceId(),
			            ORIGIN,
			            UUID.randomUUID().toString(),
			            UUID.randomUUID().toString(),
			            args
			        );
        String payload = MAPPER.writeValueAsString(signal);
        String routingKey = ControlPlaneRouting.signal("config-update", IDENTITY.swarmId(), IDENTITY.role(), IDENTITY.instanceId());

        runtime.handle(payload, routingKey);

        Map<String, Object> rawConfig = runtime.workerRawConfig(definition.beanName());
        assertThat(rawConfig)
            .containsEntry("enabled", true)
            .containsEntry("ratePerSec", 20.0);
        assertThat(runtime.workerConfig(definition.beanName(), TestConfig.class)).contains(new TestConfig(true, 20.0));
    }

    @Test
    void nullValuedConfigEntriesAreIgnored() throws Exception {
        TestConfig defaults = new TestConfig(true, 7.5);
        runtime.registerDefaultConfig(definition.beanName(), defaults);
        reset(emitter);

        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("ratePerSec", null);
	        Map<String, Object> args = data;
			        ControlSignal signal = ControlSignal.forInstance(
			            "config-update",
			            IDENTITY.swarmId(),
			            IDENTITY.role(),
			            IDENTITY.instanceId(),
			            ORIGIN,
			            UUID.randomUUID().toString(),
			            UUID.randomUUID().toString(),
			            args
			        );
        String payload = MAPPER.writeValueAsString(signal);
        String routingKey = ControlPlaneRouting.signal("config-update", IDENTITY.swarmId(), IDENTITY.role(), IDENTITY.instanceId());

        runtime.handle(payload, routingKey);

        Map<String, Object> rawConfig = runtime.workerRawConfig(definition.beanName());
        assertThat(rawConfig)
            .containsEntry("enabled", true)
            .containsEntry("ratePerSec", 7.5);
        assertThat(runtime.workerConfig(definition.beanName(), TestConfig.class)).contains(defaults);
    }

    @Test
    void stateListenerReceivesSnapshots() throws Exception {
        AtomicReference<WorkerControlPlaneRuntime.WorkerStateSnapshot> lastSnapshot = new AtomicReference<>();
        runtime.registerStateListener(definition.beanName(), lastSnapshot::set);
        WorkerControlPlaneRuntime.WorkerStateSnapshot initial = lastSnapshot.get();
        assertThat(initial).isNotNull();
        assertThat(initial.enabled()).isFalse();
        assertThat(initial.description()).contains("Test worker");
        assertThat(initial.capabilities()).containsExactlyElementsOf(definition.capabilities());
        assertThat(initial.inputType()).isEqualTo(definition.input());
        assertThat(initial.outputType()).isEqualTo(definition.outputType());
        assertThat(initial.inboundQueue()).isEmpty();
        assertThat(initial.outboundQueue()).contains(definition.io().outboundQueue());
        assertThat(initial.exchange()).contains(definition.io().outboundExchange());

	        Map<String, Object> args = Map.of("enabled", true);
			        ControlSignal signal = ControlSignal.forInstance(
			            "config-update",
			            IDENTITY.swarmId(),
			            IDENTITY.role(),
			            IDENTITY.instanceId(),
			            ORIGIN,
			            UUID.randomUUID().toString(),
			            UUID.randomUUID().toString(),
			            args
			        );
        String payload = MAPPER.writeValueAsString(signal);
        String routingKey = ControlPlaneRouting.signal("config-update", IDENTITY.swarmId(), IDENTITY.role(), IDENTITY.instanceId());

        runtime.handle(payload, routingKey);

        WorkerControlPlaneRuntime.WorkerStateSnapshot snapshot = lastSnapshot.get();
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.enabled()).isTrue();
    }

    @Test
	    void statusSnapshotsReflectWorkerEnablement() throws Exception {
	        runtime.emitStatusSnapshot();

        ArgumentCaptor<ControlPlaneEmitter.StatusContext> statusCaptor =
            ArgumentCaptor.forClass(ControlPlaneEmitter.StatusContext.class);
        verify(emitter).emitStatusSnapshot(statusCaptor.capture());

		        Map<String, Object> initialSnapshot = buildSnapshot(statusCaptor.getValue());
		        @SuppressWarnings("unchecked")
		        Map<String, Object> initialData = (Map<String, Object>) initialSnapshot.get("data");
		        assertThat(initialData.get("enabled")).isEqualTo(false);
		        assertThat(initialData).isNotNull();
		        assertThat(initialData.get("config")).isNotNull();

	        reset(emitter);

	        Map<String, Object> args = Map.of("enabled", true);
		        ControlSignal signal = ControlSignal.forInstance(
		            "config-update",
		            IDENTITY.swarmId(),
		            IDENTITY.role(),
		            IDENTITY.instanceId(),
		            ORIGIN,
		            UUID.randomUUID().toString(),
		            UUID.randomUUID().toString(),
		            args
		        );
        String payload = MAPPER.writeValueAsString(signal);
        String routingKey = ControlPlaneRouting.signal("config-update", IDENTITY.swarmId(), IDENTITY.role(), IDENTITY.instanceId());

        runtime.handle(payload, routingKey);

        reset(emitter);
        runtime.emitStatusSnapshot();

        ArgumentCaptor<ControlPlaneEmitter.StatusContext> updatedCaptor =
            ArgumentCaptor.forClass(ControlPlaneEmitter.StatusContext.class);
        verify(emitter).emitStatusSnapshot(updatedCaptor.capture());

		        Map<String, Object> updatedSnapshot = buildSnapshot(updatedCaptor.getValue());
		        @SuppressWarnings("unchecked")
		        Map<String, Object> updatedData = (Map<String, Object>) updatedSnapshot.get("data");
		        assertThat(updatedData.get("enabled")).isEqualTo(true);
		        assertThat(updatedData).isNotNull();
		        assertThat(updatedData.get("config")).isNotNull();
	    }

    @Test
    void statusSnapshotIncludesWorkerMetadata() throws Exception {
        runtime.emitStatusSnapshot();

        ArgumentCaptor<ControlPlaneEmitter.StatusContext> statusCaptor =
            ArgumentCaptor.forClass(ControlPlaneEmitter.StatusContext.class);
        verify(emitter).emitStatusSnapshot(statusCaptor.capture());

	        Map<String, Object> snapshot = buildSnapshot(statusCaptor.getValue());
	        @SuppressWarnings("unchecked")
	        Map<String, Object> data = (Map<String, Object>) snapshot.get("data");
	        assertThat(data.get("config")).isNotNull();
	        assertThat(data.get("io")).isNotNull();
	    }

	    @Test
	    void statusRequestWithoutPayloadEmitsSnapshot() throws Exception {
	        runtime.registerDefaultConfig(definition.beanName(), new TestConfig(true, 5.0));
	        reset(emitter);

	        String routingKey = ControlPlaneRouting.signal("status-request", IDENTITY.swarmId(), IDENTITY.role(), IDENTITY.instanceId());
			        ControlSignal signal = ControlSignal.forInstance(
			            "status-request",
			            IDENTITY.swarmId(),
			            IDENTITY.role(),
			            IDENTITY.instanceId(),
			            ORIGIN,
			            UUID.randomUUID().toString(),
			            null,
			            null);

	        boolean handled = runtime.handle(MAPPER.writeValueAsString(signal), routingKey);

	        assertThat(handled).isTrue();
	        verify(emitter).emitStatusSnapshot(any());
	    }

    @Test
    void controlPlaneConfigOverridesSeededDefaults() throws Exception {
        TestConfig defaults = new TestConfig(false, 3.0);
        runtime.registerDefaultConfig(definition.beanName(), defaults);

        Map<String, Object> args = Map.of(
            "enabled", true,
            "ratePerSec", 11.0
	        );
			        ControlSignal signal = ControlSignal.forInstance(
			            "config-update",
			            IDENTITY.swarmId(),
			            IDENTITY.role(),
			            IDENTITY.instanceId(),
			            ORIGIN,
			            UUID.randomUUID().toString(),
			            UUID.randomUUID().toString(),
			            args
		        );
        String payload = MAPPER.writeValueAsString(signal);
        String routingKey = ControlPlaneRouting.signal("config-update", IDENTITY.swarmId(), IDENTITY.role(), IDENTITY.instanceId());

        boolean handled = runtime.handle(payload, routingKey);

        assertThat(handled).isTrue();
        assertThat(runtime.workerConfig(definition.beanName(), TestConfig.class))
            .contains(new TestConfig(true, 11.0));
        assertThat(runtime.workerEnabled(definition.beanName())).isTrue();
        Map<String, Object> rawConfig = runtime.workerRawConfig(definition.beanName());
        assertThat(rawConfig).containsEntry("ratePerSec", 11.0);
    }

	        private String buildEnvelopeJson(ControlPlaneEmitter.StatusContext context, String type) {
	            StatusEnvelopeBuilder builder = new StatusEnvelopeBuilder()
	                .type(type)
	                .origin(IDENTITY.instanceId())
	                .swarmId(IDENTITY.swarmId())
	                .runtime(RUNTIME_META);
	            context.customiser().accept(builder);
	            return builder.toJson();
	        }

        private Map<String, Object> buildSnapshot(ControlPlaneEmitter.StatusContext context) throws Exception {
            @SuppressWarnings("unchecked")
            Map<String, Object> snapshot = MAPPER.readValue(buildEnvelopeJson(context, "status-full"), Map.class);
            return snapshot;
        }

    private static final class TestWorker {
        // marker class for definition
    }

    private record TestConfig(boolean enabled, double ratePerSec) {
    }
}
