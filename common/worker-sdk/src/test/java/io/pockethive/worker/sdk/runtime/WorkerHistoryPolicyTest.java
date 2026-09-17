package io.pockethive.worker.sdk.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.codec.ControlPlaneCodec;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.worker.WorkerControlPlane;
import io.pockethive.swarm.model.BeeRoles;
import io.pockethive.templating.api.TemplateRenderer;
import io.pockethive.work.api.HistoryPolicy;
import io.pockethive.work.api.PocketHiveWorkerFunction;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkStep;
import io.pockethive.work.api.WorkerInfo;
import io.pockethive.work.config.WorkerInputType;
import io.pockethive.work.config.WorkerOutputType;
import io.pockethive.work.config.binding.WorkInputConfig;
import io.pockethive.work.config.binding.WorkOutputConfig;
import io.pockethive.work.config.composition.CurrentWorkConfigurationProviders;
import io.pockethive.worker.sdk.testing.ControlPlaneTestFixtures;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.ArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class WorkerHistoryPolicyTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final ControlPlaneIdentity IDENTITY =
        new ControlPlaneIdentity("history-swarm", BeeRoles.PROCESSOR, "processor-1");
    private final WorkerDefinition definition = new WorkerDefinition(
        "historyWorker", PocketHiveWorkerFunction.class, WorkerInputType.SCHEDULER,
        IDENTITY.role(), WorkIoBindings.none(), Map.class, WorkInputConfig.class,
        WorkOutputConfig.class, WorkerOutputType.NONE, "History worker", Set.of());
    private final WorkerStateStore states = new WorkerStateStore();
    private final ControlPlaneEmitter emitter = mock(ControlPlaneEmitter.class);
    private final TemplateRenderer templates = mock(TemplateRenderer.class);
    private final DefaultWorkerContextFactory contexts = new DefaultWorkerContextFactory(
        type -> { throw new IllegalStateException("Unexpected bean lookup"); }, IDENTITY);
    private WorkerControlPlaneRuntime runtime;

    @BeforeEach void setUp() {
        states.getOrCreate(definition);
        var control = WorkerControlPlane.builder(ControlPlaneCodec.create()).identity(IDENTITY).build();
        var properties = ControlPlaneTestFixtures.workerProperties(
            IDENTITY.swarmId(), IDENTITY.role(), IDENTITY.instanceId());
        var work = new CurrentWorkConfigurationProviders();
        runtime = new WorkerControlPlaneRuntime(control, states, JSON, emitter, IDENTITY,
            properties.getControlPlane(), templates, work.workMutationPolicyRegistry(), work.workConfigurationParser());
    }

    @Test void usesFullWhenScenarioOmitsPolicy() throws Exception {
        update(Map.of("enabled", true));
        WorkItem input = input();
        WorkItem result = invocation().invoke(input);
        assertThat(result.steps()).extracting(WorkStep::payload).containsExactly("origin", "processed");
        assertThat(result.steps()).first().isSameAs(input.steps().iterator().next());
    }

    @Test void changesRetentionWhenAcceptedScenarioPolicyChanges() throws Exception {
        update(Map.of("enabled", true, "historyPolicy", HistoryPolicy.LATEST_ONLY.name()));
        var invocation = invocation();
        WorkItem input = input();
        WorkItem latest = invocation.invoke(input);
        assertThat(latest.steps()).extracting(WorkStep::payload).containsExactly("processed");
        assertThat(latest.steps()).extracting(WorkStep::index).containsExactly(0);
        assertThat(latest.stepHeaders()).containsEntry(WorkItem.STEP_INSTANCE_HEADER, IDENTITY.instanceId());
        assertThat(latest.headers()).isEqualTo(input.headers());

        update(Map.of("historyPolicy", HistoryPolicy.FULL.name()));
        assertThat(invocation.invoke(input).steps()).extracting(WorkStep::payload)
            .containsExactly("origin", "processed");
        assertThat(runtime.workerRawConfig(definition.beanName()))
            .containsEntry("historyPolicy", HistoryPolicy.FULL.name());
    }

    @Test void preservesPolicyAcrossPartialUpdatesAndRestoresDefaultOnExplicitReset() throws Exception {
        update(Map.of("enabled", true, "historyPolicy", HistoryPolicy.LATEST_ONLY.name()));
        update(Map.of("probe", "changed"));
        update(Map.of("enabled", false));
        assertThat(invocation().invoke(input())).isNull();
        update(Map.of("enabled", true));
        assertThat(invocation().invoke(input()).steps()).hasSize(1);
        update(Map.of("workers", Map.of(definition.beanName(), Map.of())));
        assertThat(runtime.workerRawConfig(definition.beanName())).isEmpty();
        assertThat(invocation().invoke(input()).steps()).hasSize(2);
    }

    @Test void keepsPolicyCapturedForAnInvocationWhenControlUpdatesDuringItsExecution() throws Exception {
        update(Map.of("enabled", true, "historyPolicy", HistoryPolicy.LATEST_ONLY.name()));
        PocketHiveWorkerFunction worker = (input, context) -> {
            update(Map.of("historyPolicy", HistoryPolicy.FULL.name()));
            return input.addStepPayload("processed");
        };
        var invocation = new WorkerInvocation(worker, contexts, definition, states.getOrCreate(definition), List.of());
        assertThat(invocation.invoke(input()).steps()).hasSize(1);
        assertThat(invocation.invoke(input()).steps()).hasSize(2);
    }

    @ParameterizedTest @MethodSource("invalidPolicies")
    void rejectsInvalidPolicyBeforeChangingAcceptedConfigurationOrEnablement(Object invalid) throws Exception {
        update(Map.of("enabled", true, "historyPolicy", HistoryPolicy.LATEST_ONLY.name(), "probe", "accepted"));
        var acceptedRaw = runtime.workerRawConfig(definition.beanName());
        var acceptedTyped = runtime.workerConfig(definition.beanName(), Map.class);
        List<Map<String, Object>> observations = new ArrayList<>();
        runtime.registerStateListener(definition.beanName(), snapshot -> observations.add(snapshot.rawConfig()));
        reset(emitter, templates);

        var rejected = new LinkedHashMap<String, Object>(Map.of(
            "enabled", false, "probe", "rejected", "templating", Map.of("reseed", true)));
        rejected.put("historyPolicy", invalid);
        update(rejected);

        verify(emitter).emitFailure(any());
        verify(emitter, never()).emitResult(any());
        verify(templates, never()).resetSeededSelections();
        assertThat(runtime.workerRawConfig(definition.beanName())).isEqualTo(acceptedRaw);
        assertThat(runtime.workerConfig(definition.beanName(), Map.class)).isEqualTo(acceptedTyped);
        assertThat(runtime.workerEnabled(definition.beanName())).isTrue();
        assertThat(observations).isNotEmpty().allSatisfy(raw -> assertThat(raw).isEqualTo(acceptedRaw));
        assertThat(invocation().invoke(input()).steps()).hasSize(1);
    }

    static Stream<Object> invalidPolicies() {
        return Stream.of(null, "DISABLED", "full", "", "LATEST", 1, true, List.of("FULL"));
    }

    private WorkerInvocation invocation() {
        PocketHiveWorkerFunction worker = (input, context) -> input.addStepPayload("processed");
        return new WorkerInvocation(worker, contexts, definition, states.getOrCreate(definition), List.of());
    }

    private static WorkItem input() {
        return WorkItem.text(new WorkerInfo(BeeRoles.GENERATOR, IDENTITY.swarmId(), "generator-1", null, null), "origin")
            .header("correlationId", "history-correlation").build();
    }

    private void update(Map<String, Object> config) throws Exception {
        var signal = ControlSignal.forInstance(ControlPlaneSignals.CONFIG_UPDATE,
            IDENTITY.swarmId(), IDENTITY.role(), IDENTITY.instanceId(), "orchestrator-1",
            UUID.randomUUID().toString(), UUID.randomUUID().toString(), config);
        String routingKey = ControlPlaneRouting.signal(
            ControlPlaneSignals.CONFIG_UPDATE, IDENTITY.swarmId(), IDENTITY.role(), IDENTITY.instanceId());
        assertThat(runtime.handle(ControlPlaneCodec.create().encode(signal, routingKey), routingKey)).isTrue();
    }
}
