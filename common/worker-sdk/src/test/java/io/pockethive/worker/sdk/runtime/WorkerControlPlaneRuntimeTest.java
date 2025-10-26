package io.pockethive.worker.sdk.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.CommandTarget;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.worker.WorkerControlPlane;
import io.pockethive.observability.StatusEnvelopeBuilder;
import io.pockethive.worker.sdk.capabilities.WorkerCapabilitiesManifestRepository;
import io.pockethive.worker.sdk.config.WorkerType;
import io.pockethive.worker.sdk.testing.ControlPlaneTestFixtures;
import io.pockethive.controlplane.spring.WorkerControlPlaneProperties;
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
    private static final String SWARM_ID = "default";
    private static final ControlPlaneIdentity IDENTITY = new ControlPlaneIdentity(SWARM_ID, "generator", "inst-1");
    private static final WorkerControlPlaneProperties PROPERTIES =
        ControlPlaneTestFixtures.workerProperties(SWARM_ID, "generator", "inst-1");

    private WorkerStateStore stateStore;
    private WorkerDefinition definition;
    private WorkerControlPlane controlPlane;
    private WorkerControlPlaneRuntime runtime;
    private WorkerCapabilitiesManifestRepository manifestRepository;

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
            "traffic.exchange",
            TestConfig.class
        );
        stateStore.getOrCreate(definition);
        controlPlane = WorkerControlPlane.builder(MAPPER)
            .identity(IDENTITY)
            .build();
        manifestRepository = new WorkerCapabilitiesManifestRepository(MAPPER);
        runtime = new WorkerControlPlaneRuntime(controlPlane, stateStore, MAPPER, emitter, IDENTITY,
            manifestRepository, PROPERTIES.getControlPlane());
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
        assertThat(data).containsKey("capabilities");
        @SuppressWarnings("unchecked")
        Map<String, Object> capabilities = (Map<String, Object>) data.get("capabilities");
        assertThat(capabilities).containsKey("generator");
        @SuppressWarnings("unchecked")
        Map<String, Object> generatorManifest = (Map<String, Object>) capabilities.get("generator");
        assertThat(generatorManifest.get("capabilitiesVersion")).isEqualTo("1.0.0");
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
    void explicitEmptyPayloadResetsWorkerConfig() throws Exception {
        Map<String, Object> initialArgs = Map.of(
            "data", Map.of(
                "enabled", true,
                "ratePerSec", 15.0
            )
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
        String routingKey = ControlPlaneRouting.signal("config-update", IDENTITY.swarmId(), IDENTITY.role(), IDENTITY.instanceId());
        runtime.handle(MAPPER.writeValueAsString(initialSignal), routingKey);

        Map<String, Object> resetArgs = Map.of(
            "data", Map.of(
                "workers", Map.of(definition.beanName(), Map.of())
            )
        );
        ControlSignal resetSignal = ControlSignal.forInstance(
            "config-update",
            IDENTITY.swarmId(),
            IDENTITY.role(),
            IDENTITY.instanceId(),
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            CommandTarget.INSTANCE,
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
            WorkerType.GENERATOR,
            "generator",
            null,
            "out.queue",
            "traffic.exchange",
            TestConfig.class
        );
        stateStore.getOrCreate(otherDefinition);
        String routingKey = ControlPlaneRouting.signal("config-update", IDENTITY.swarmId(), IDENTITY.role(), IDENTITY.instanceId());

        Map<String, Object> firstWorkerArgs = Map.of(
            "worker", definition.beanName(),
            "data", Map.of("ratePerSec", 17.5)
        );
        ControlSignal firstWorkerSignal = ControlSignal.forInstance(
            "config-update",
            IDENTITY.swarmId(),
            IDENTITY.role(),
            IDENTITY.instanceId(),
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            CommandTarget.INSTANCE,
            firstWorkerArgs
        );
        runtime.handle(MAPPER.writeValueAsString(firstWorkerSignal), routingKey);

        Map<String, Object> secondWorkerArgs = Map.of(
            "worker", otherDefinition.beanName(),
            "data", Map.of("ratePerSec", 42.0)
        );
        ControlSignal secondWorkerSignal = ControlSignal.forInstance(
            "config-update",
            IDENTITY.swarmId(),
            IDENTITY.role(),
            IDENTITY.instanceId(),
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            CommandTarget.INSTANCE,
            secondWorkerArgs
        );
        runtime.handle(MAPPER.writeValueAsString(secondWorkerSignal), routingKey);

        Map<String, Object> broadcastArgs = Map.of(
            "data", Map.of(
                "workers", Map.of(
                    definition.beanName(), Map.of("ratePerSec", 99.0)
                )
            )
        );
        ControlSignal broadcastSignal = ControlSignal.forInstance(
            "config-update",
            IDENTITY.swarmId(),
            IDENTITY.role(),
            IDENTITY.instanceId(),
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            CommandTarget.ROLE,
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
            "data", Map.of("ratePerSec", 18.0)
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
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            CommandTarget.INSTANCE,
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
            "data", Map.of(
                "enabled", true,
                "ratePerSec", 9.5
            )
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
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            CommandTarget.INSTANCE,
            toggleArgs
        );

        runtime.handle(MAPPER.writeValueAsString(toggleSignal), routingKey);

        assertThat(runtime.workerEnabled(definition.beanName())).contains(false);
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
            "data", Map.of("ratePerSec", 20.0)
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
        Map<String, Object> args = Map.of("data", data);
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
        assertThat(initialData).containsKey("capabilities");
        @SuppressWarnings("unchecked")
        Map<String, Object> initialCapabilities = (Map<String, Object>) initialData.get("capabilities");
        assertThat(initialCapabilities).containsKey("generator");
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
        assertThat(updatedData).doesNotContainKey("capabilities");
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> updatedWorkers =
            (java.util.List<Map<String, Object>>) updatedData.get("workers");
        assertThat(updatedWorkers).hasSize(1);
        assertThat(updatedWorkers.get(0).get("enabled")).isEqualTo(true);
    }

    @Test
    void statusDeltaOmitsCapabilitiesAfterInitialPublish() throws Exception {
        runtime.emitStatusSnapshot();
        reset(emitter);

        runtime.emitStatusDelta();

        ArgumentCaptor<ControlPlaneEmitter.StatusContext> deltaCaptor =
            ArgumentCaptor.forClass(ControlPlaneEmitter.StatusContext.class);
        verify(emitter).emitStatusDelta(deltaCaptor.capture());

        Map<String, Object> deltaSnapshot = buildSnapshot(deltaCaptor.getValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> deltaData = (Map<String, Object>) deltaSnapshot.get("data");
        assertThat(deltaData).isNotNull();
        assertThat(deltaData).doesNotContainKey("capabilities");
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
