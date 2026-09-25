package io.pockethive.worker.sdk.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlSignal;
import io.pockethive.control.StatusMetric;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.codec.ControlPlaneCodec;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.messaging.EventMessage;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.topology.ControlPlaneTopologySettings;
import io.pockethive.controlplane.worker.WorkerControlPlane;
import io.pockethive.rabbit.api.RabbitResourceNames;
import io.pockethive.swarm.model.BeeRoles;
import io.pockethive.work.api.HistoryPolicy;
import io.pockethive.work.api.PocketHiveWorkerFunction;
import io.pockethive.work.config.WorkerInputType;
import io.pockethive.work.config.WorkerOutputType;
import io.pockethive.work.config.binding.WorkInputConfig;
import io.pockethive.work.config.binding.WorkOutputConfig;
import io.pockethive.work.config.composition.CurrentWorkConfigurationProviders;
import io.pockethive.worker.sdk.testing.ControlPlaneTestFixtures;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

/**
 * Responsibility: verify accepted configuration and metadata in producer-generated full/delta status.
 * Must not: implement a CP subscriber, status builder or wire validator for acceptance tests.
 * Contract: RESP-WORK-STATUS — docs/architecture/runtime-responsibilities.md#resp-work-status; WK-4.
 */
class WorkerStatusContractTest {
  @ParameterizedTest @MethodSource("roles")
  void fullIncludesAcceptedConfigAndRuntimeWhileDeltaOmitsConfig(String role) {
    var identity = new ControlPlaneIdentity("status-swarm", role, "worker-1");
    var codec = ControlPlaneCodec.create();
    var publisher = mock(ControlPlanePublisher.class);
    var metadata = Map.<String, Object>of("templateId", "status-fixture", "runId", "status-run",
        "containerId", "container-1", "image", "worker:acceptance", "stackName", "status-stack");
    var emitter = ControlPlaneEmitter.worker(identity, publisher,
        new ControlPlaneTopologySettings(identity.swarmId(), "ph.control", Map.of()), metadata,
        new RabbitResourceNames());
    var definition = new WorkerDefinition("statusWorker", PocketHiveWorkerFunction.class,
        WorkerInputType.SCHEDULER, role, WorkIoBindings.none(), Map.class, WorkInputConfig.class,
        WorkOutputConfig.class, WorkerOutputType.NONE, "Status contract fixture", Set.of());
    var states = new WorkerStateStore();
    states.getOrCreate(definition);
    var work = new CurrentWorkConfigurationProviders();
    var properties = ControlPlaneTestFixtures.workerProperties(identity.swarmId(), role, identity.instanceId());
    var runtime = new WorkerControlPlaneRuntime(
        WorkerControlPlane.builder(codec).identity(identity).build(), states, new ObjectMapper(), emitter, identity,
        properties.getControlPlane(), null, work.workMutationPolicyRegistry(), work.workConfigurationParser(), new io.pockethive.worker.sdk.config.RedisSequenceConfiguration(new io.pockethive.worker.sdk.config.RedisSequenceProperties()));
    var config = Map.<String, Object>of("enabled", true, "historyPolicy", HistoryPolicy.LATEST_ONLY.name(),
        "message", Map.of("body", "accepted-status-probe"));
    var signal = ControlSignal.forInstance(ControlPlaneSignals.CONFIG_UPDATE, identity.swarmId(), role,
        identity.instanceId(), "controller-1", UUID.randomUUID().toString(), UUID.randomUUID().toString(), config);
    String route = ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, identity.swarmId(), role,
        identity.instanceId());
    assertThat(runtime.handle(codec.encode(signal, route), route)).isTrue();
    assertThat(runtime.workerRawConfig(definition.beanName())).isEqualTo(config);
    clearInvocations(publisher);

    runtime.emitStatusSnapshot();
    runtime.emitStatusDelta();
    runtime.emitStatusSnapshot();

    var captured = ArgumentCaptor.forClass(EventMessage.class);
    verify(publisher, times(3)).publishEvent(captured.capture());
    var statuses = captured.getAllValues().stream().map(event -> codec.decode(
        codec.encode(event.payload(), event.routingKey()), event.routingKey(), StatusMetric.class)).toList();
    assertThat(statuses).extracting(StatusMetric::type)
        .containsExactly(StatusMetric.STATUS_FULL, StatusMetric.STATUS_DELTA, StatusMetric.STATUS_FULL);
    var json = new ObjectMapper().findAndRegisterModules();
    for (int i = 0; i < statuses.size(); i++) {
      var status = statuses.get(i);
      assertThat(status.scope().swarmId()).isEqualTo(identity.swarmId());
      assertThat(status.scope().role()).isEqualTo(role);
      assertThat(status.scope().instance()).isEqualTo(identity.instanceId());
      assertThat(status.runtime()).isEqualTo(metadata);
      var data = json.valueToTree(status).required("data");
      assertThat(data.required("enabled").booleanValue()).isTrue();
      if (i == 1) {
        assertThat(data.has("config")).isFalse();
      } else {
        assertThat(data.required("config")).isEqualTo(json.valueToTree(config));
        assertThat(data.hasNonNull("startedAt")).isTrue();
      }
    }
  }

  static List<String> roles() {
    return List.of(BeeRoles.GENERATOR, BeeRoles.MODERATOR, BeeRoles.PROCESSOR, BeeRoles.POSTPROCESSOR);
  }
}
