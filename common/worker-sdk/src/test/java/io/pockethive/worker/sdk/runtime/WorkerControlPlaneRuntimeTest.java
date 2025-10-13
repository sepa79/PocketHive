package io.pockethive.worker.sdk.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.Topology;
import io.pockethive.control.CommandTarget;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.GeneratorControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.worker.WorkerControlPlane;
import io.pockethive.observability.StatusEnvelopeBuilder;
import io.pockethive.worker.sdk.config.WorkerType;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class WorkerControlPlaneRuntimeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ControlPlaneIdentity IDENTITY = new ControlPlaneIdentity(Topology.SWARM_ID, "generator", "inst-1");
    private static final ControlPlaneTopologyDescriptor TOPOLOGY = new GeneratorControlPlaneTopologyDescriptor();

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
            WorkerType.GENERATOR,
            "generator",
            null,
            "out.queue",
            TestConfig.class
        );
        stateStore.getOrCreate(definition);
        controlPlane = WorkerControlPlane.builder(MAPPER)
            .identity(IDENTITY)
            .build();
        runtime = new WorkerControlPlaneRuntime(controlPlane, stateStore, MAPPER, emitter, IDENTITY, TOPOLOGY);
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
        assertThat(runtime.workerEnabled(definition.beanName())).contains(true);

        reset(emitter);
        runtime.emitStatusSnapshot();

        ArgumentCaptor<ControlPlaneEmitter.StatusContext> captor =
            ArgumentCaptor.forClass(ControlPlaneEmitter.StatusContext.class);
        verify(emitter).emitStatusSnapshot(captor.capture());

        Map<String, Object> snapshot = buildSnapshot(captor.getValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) snapshot.get("data");
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> workers = (java.util.List<Map<String, Object>>) data.get("workers");
        assertThat(workers).singleElement().satisfies(worker ->
            assertThat(worker).containsEntry("worker", definition.beanName()).containsKey("config"));
    }

    @Test
    void workerConfigAccessibleAfterUpdate() throws Exception {
        String correlationId = UUID.randomUUID().toString();
        String idempotencyKey = UUID.randomUUID().toString();
        Map<String, Object> args = Map.of(
            "data", Map.of(
                "enabled", true,
                "ratePerSec", 12.5
            )
        );
        ControlSignal signal = ControlSignal.forInstance(
            "config-update",
            IDENTITY.swarmId(),
            IDENTITY.role(),
            IDENTITY.instanceId(),
            correlationId,
            idempotencyKey,
            CommandTarget.INSTANCE,
            args
        );
        String payload = MAPPER.writeValueAsString(signal);
        String routingKey = ControlPlaneRouting.signal("config-update", IDENTITY.swarmId(), IDENTITY.role(), IDENTITY.instanceId());

        boolean handled = runtime.handle(payload, routingKey);

        assertThat(handled).isTrue();
        Optional<TestConfig> config = runtime.workerConfig(definition.beanName(), TestConfig.class);
        assertThat(config).contains(new TestConfig(true, 12.5));
        assertThat(runtime.workerEnabled(definition.beanName())).contains(true);
        Map<String, Object> rawConfig = runtime.workerRawConfig(definition.beanName());
        assertThat(rawConfig).containsEntry("ratePerSec", 12.5);
        ArgumentCaptor<ControlPlaneEmitter.ReadyContext> captor = ArgumentCaptor.forClass(ControlPlaneEmitter.ReadyContext.class);
        verify(emitter).emitReady(captor.capture());
        assertThat(captor.getValue().signal()).isEqualTo("config-update");
    }

    @Test
    void configUpdateWithoutEnabledPreservesExistingState() throws Exception {
        Map<String, Object> initialArgs = Map.of(
            "data", Map.of("enabled", true)
        );
        ControlSignal initialSignal = ControlSignal.forInstance(
            "config-update",
            IDENTITY.swarmId(),
            IDENTITY.role(),
            IDENTITY.instanceId(),
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            CommandTarget.INSTANCE,
            initialArgs
        );
        String initialPayload = MAPPER.writeValueAsString(initialSignal);
        String routingKey = ControlPlaneRouting.signal("config-update", IDENTITY.swarmId(), IDENTITY.role(), IDENTITY.instanceId());

        runtime.handle(initialPayload, routingKey);

        assertThat(runtime.workerEnabled(definition.beanName())).contains(true);

        Map<String, Object> updateArgs = Map.of(
            "data", Map.of("ratePerSec", 20.0)
        );
        ControlSignal updateSignal = ControlSignal.forInstance(
            "config-update",
            IDENTITY.swarmId(),
            IDENTITY.role(),
            IDENTITY.instanceId(),
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            CommandTarget.INSTANCE,
            updateArgs
        );
        String updatePayload = MAPPER.writeValueAsString(updateSignal);

        runtime.handle(updatePayload, routingKey);

        assertThat(runtime.workerEnabled(definition.beanName())).contains(true);
        Map<String, Object> rawConfig = runtime.workerRawConfig(definition.beanName());
        assertThat(rawConfig).containsEntry("ratePerSec", 20.0);
    }

    @Test
    void stateListenerReceivesSnapshots() throws Exception {
        AtomicReference<WorkerControlPlaneRuntime.WorkerStateSnapshot> lastSnapshot = new AtomicReference<>();
        runtime.registerStateListener(definition.beanName(), lastSnapshot::set);
        WorkerControlPlaneRuntime.WorkerStateSnapshot initial = lastSnapshot.get();
        assertThat(initial).isNotNull();
        assertThat(initial.enabled()).isEmpty();

        Map<String, Object> args = Map.of(
            "data", Map.of("enabled", true)
        );
        ControlSignal signal = ControlSignal.forInstance(
            "config-update",
            IDENTITY.swarmId(),
            IDENTITY.role(),
            IDENTITY.instanceId(),
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            CommandTarget.INSTANCE,
            args
        );
        String payload = MAPPER.writeValueAsString(signal);
        String routingKey = ControlPlaneRouting.signal("config-update", IDENTITY.swarmId(), IDENTITY.role(), IDENTITY.instanceId());

        runtime.handle(payload, routingKey);

        WorkerControlPlaneRuntime.WorkerStateSnapshot snapshot = lastSnapshot.get();
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.enabled()).contains(true);
    }

    @Test
    void statusSnapshotsReflectWorkerEnablement() throws Exception {
        runtime.emitStatusSnapshot();

        ArgumentCaptor<ControlPlaneEmitter.StatusContext> statusCaptor =
            ArgumentCaptor.forClass(ControlPlaneEmitter.StatusContext.class);
        verify(emitter).emitStatusSnapshot(statusCaptor.capture());

        Map<String, Object> initialSnapshot = buildSnapshot(statusCaptor.getValue());
        assertThat(initialSnapshot.get("enabled")).isEqualTo(false);

        @SuppressWarnings("unchecked")
        Map<String, Object> initialData = (Map<String, Object>) initialSnapshot.get("data");
        assertThat(initialData).isNotNull();
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> initialWorkers =
            (java.util.List<Map<String, Object>>) initialData.get("workers");
        assertThat(initialWorkers).hasSize(1);
        assertThat(initialWorkers.get(0).get("enabled")).isEqualTo(false);

        reset(emitter);

        Map<String, Object> args = Map.of(
            "data", Map.of("enabled", true)
        );
        ControlSignal signal = ControlSignal.forInstance(
            "config-update",
            IDENTITY.swarmId(),
            IDENTITY.role(),
            IDENTITY.instanceId(),
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            CommandTarget.INSTANCE,
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
        assertThat(updatedSnapshot.get("enabled")).isEqualTo(true);

        @SuppressWarnings("unchecked")
        Map<String, Object> updatedData = (Map<String, Object>) updatedSnapshot.get("data");
        assertThat(updatedData).isNotNull();
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> updatedWorkers =
            (java.util.List<Map<String, Object>>) updatedData.get("workers");
        assertThat(updatedWorkers).hasSize(1);
        assertThat(updatedWorkers.get(0).get("enabled")).isEqualTo(true);
    }

    private Map<String, Object> buildSnapshot(ControlPlaneEmitter.StatusContext context) throws Exception {
        StatusEnvelopeBuilder builder = new StatusEnvelopeBuilder();
        context.customiser().accept(builder);
        @SuppressWarnings("unchecked")
        Map<String, Object> snapshot = MAPPER.readValue(builder.toJson(), Map.class);
        return snapshot;
    }

    private static final class TestWorker {
        // marker class for definition
    }

    private record TestConfig(boolean enabled, double ratePerSec) {
    }
}
