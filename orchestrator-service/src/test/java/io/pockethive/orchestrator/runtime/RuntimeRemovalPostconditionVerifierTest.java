package io.pockethive.orchestrator.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.ComputeRuntimeInventoryPort;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.ComputeRuntimeResource;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.RabbitExchangeResource;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.RabbitQueueResource;
import io.pockethive.orchestrator.runtime.RabbitTopologyPort;
import io.pockethive.swarm.model.lifecycle.RemoveResource;
import io.pockethive.swarm.model.lifecycle.RemoveResourceType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RuntimeRemovalPostconditionVerifierTest {

  @Test
  void nativeWorkRemovalRequiresObservedAbsenceAndNeverQueriesRabbit() {
    var transport = new io.pockethive.worker.sdk.testing.InMemoryWorkTransport();
    var owner = new io.pockethive.worker.sdk.testing.InMemoryWorkResources(transport, "verification");
    var topology = new io.pockethive.worker.sdk.testing.InMemoryWorkTopologyResolver().resolve("swarm", java.util.Set.of("jobs"));
    owner.ensure(topology);
    var resource = topology.channel("jobs").resource();
    var target = owner.removalTarget(resource);
    var nativeVerifier = new RuntimeRemovalPostconditionVerifier(compute, rabbit, owner);
    assertThat(target.type()).isEqualTo(RemoveResourceType.WORK_RESOURCE);
    assertThat(target.id()).isEqualTo("memory://swarm/jobs");
    assertThat(nativeVerifier.verifyAbsent(List.of(target)).remainingResources()).containsExactly(target);
    var input = transport.input(resource.name());
    input.register(mock(io.pockethive.work.api.transport.WorkDeliveryHandler.class));
    input.start();
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> owner.remove(resource)).hasMessage("Input is running");
    assertThat(nativeVerifier.verifyAbsent(List.of(target)).succeeded()).isFalse();
    input.stop();
    owner.remove(resource);
    assertThat(nativeVerifier.verifyAbsent(List.of(target)).removedResources()).containsExactly(target);
    org.mockito.Mockito.verifyNoInteractions(rabbit, compute);
  }

  private final ComputeRuntimeInventoryPort compute = mock(ComputeRuntimeInventoryPort.class);
  private final RabbitTopologyPort rabbit = mock(RabbitTopologyPort.class);
  private final io.pockethive.rabbit.api.RabbitResources workBroker = mock(io.pockethive.rabbit.api.RabbitResources.class);
  private final RuntimeRemovalPostconditionVerifier verifier =
      new RuntimeRemovalPostconditionVerifier(compute, rabbit, new io.pockethive.rabbit.work.RabbitWorkResources(workBroker,
          new io.pockethive.rabbit.api.RabbitConnectionSettings("work", 5672, "user", "secret", "/work")));

  @Test
  void confirmsAbsenceOnlyFromCurrentAdapterObservations() {
    when(compute.list()).thenReturn(List.of());
    when(workBroker.queue("queue-1")).thenReturn(Optional.empty());
    when(workBroker.exchangeExists("exchange-1")).thenReturn(false);
    List<RemoveResource> targets = List.of(
        resource(RemoveResourceType.WORKER_RUNTIME, "worker-1"),
        resource(RemoveResourceType.RABBIT_QUEUE, "queue-1"),
        resource(RemoveResourceType.RABBIT_EXCHANGE, "exchange-1"));

    var result = verifier.verifyAbsent(targets);

    assertThat(result.succeeded()).isTrue();
    assertThat(result.removedResources()).containsExactlyElementsOf(targets);
    assertThat(result.remainingResources()).isEmpty();
    assertThat(result.errors()).isEmpty();
  }

  @Test
  void reportsEveryResourceThatStillExists() {
    when(compute.list()).thenReturn(List.of(new ComputeRuntimeResource(
        "worker-1", "container", "worker", "image", "running", Map.of())));
    when(workBroker.queue("queue-1")).thenReturn(Optional.of(new io.pockethive.rabbit.api.RabbitQueueObservation(0, 0, java.util.OptionalLong.empty())));
    when(workBroker.exchangeExists("exchange-1")).thenReturn(true);
    List<RemoveResource> targets = List.of(
        resource(RemoveResourceType.WORKER_RUNTIME, "worker-1"),
        resource(RemoveResourceType.RABBIT_QUEUE, "queue-1"),
        resource(RemoveResourceType.RABBIT_EXCHANGE, "exchange-1"));

    var result = verifier.verifyAbsent(targets);

    assertThat(result.succeeded()).isFalse();
    assertThat(result.remainingResources()).containsExactlyElementsOf(targets);
    assertThat(result.errors()).extracting("code")
        .containsExactly("RESOURCE_STILL_PRESENT", "RESOURCE_STILL_PRESENT", "RESOURCE_STILL_PRESENT");
  }

  @Test
  void turnsObservationFailureIntoExplicitRemainingEvidence() {
    when(compute.list()).thenThrow(new IllegalStateException("runtime inventory unavailable"));
    RemoveResource worker = resource(RemoveResourceType.WORKER_RUNTIME, "worker-1");

    var result = verifier.verifyAbsent(List.of(worker));

    assertThat(result.succeeded()).isFalse();
    assertThat(result.remainingResources()).containsExactly(worker);
    assertThat(result.errors()).singleElement().satisfies(error -> {
      assertThat(error.code()).isEqualTo("IllegalStateException");
      assertThat(error.message()).contains("runtime inventory unavailable");
    });
  }

  @Test
  void rejectsNetworkBindingBecauseNetworkProxyManagerOwnsThatPostcondition() {
    RemoveResource binding = resource(RemoveResourceType.NETWORK_BINDING, "swarm-1");

    var result = verifier.verifyAbsent(List.of(binding));

    assertThat(result.succeeded()).isFalse();
    assertThat(result.remainingResources()).containsExactly(binding);
    assertThat(result.errors()).singleElement().satisfies(error ->
        assertThat(error.message()).contains("later remove postcondition stage"));
  }

  @Test
  void workObservationFailureCannotBecomeAbsenceOrUseControlAsAReplacement() {
    when(workBroker.queue("jobs")).thenThrow(new IllegalStateException("work unavailable"));
    var target = resource(RemoveResourceType.RABBIT_QUEUE, "jobs");
    var result = verifier.verifyAbsent(List.of(target));
    assertThat(result.succeeded()).isFalse();
    assertThat(result.removedResources()).isEmpty();
    assertThat(result.remainingResources()).containsExactly(target);
    assertThat(result.errors()).singleElement().satisfies(error -> assertThat(error.message()).isEqualTo("work unavailable"));
    org.mockito.Mockito.verifyNoInteractions(rabbit, compute);
  }

  private static RemoveResource resource(RemoveResourceType type, String id) {
    return new RemoveResource(type, id, type == RemoveResourceType.RABBIT_QUEUE || type == RemoveResourceType.RABBIT_EXCHANGE ? io.pockethive.swarm.model.lifecycle.ResourcePlane.WORK : io.pockethive.swarm.model.lifecycle.ResourcePlane.NONE);
  }
  @Test
  void absenceOnWorkDoesNotConfirmControlWithTheSameName() {
    var control = new RemoveResource(RemoveResourceType.RABBIT_QUEUE, "jobs", io.pockethive.swarm.model.lifecycle.ResourcePlane.CONTROL);
    var work = new RemoveResource(RemoveResourceType.RABBIT_QUEUE, "jobs", io.pockethive.swarm.model.lifecycle.ResourcePlane.WORK);
    when(rabbit.queue(control.plane(), "jobs")).thenReturn(Optional.of(new RabbitQueueResource("jobs", 0, 0)));
    when(workBroker.queue("jobs")).thenReturn(Optional.empty());
    var result = verifier.verifyAbsent(List.of(control, work));
    assertThat(result.removedResources()).containsExactly(work);
    assertThat(result.remainingResources()).containsExactly(control);
    assertThat(result.succeeded()).isFalse();
  }

}
