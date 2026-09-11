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

  private final ComputeRuntimeInventoryPort compute = mock(ComputeRuntimeInventoryPort.class);
  private final RabbitTopologyPort rabbit = mock(RabbitTopologyPort.class);
  private final RuntimeRemovalPostconditionVerifier verifier =
      new RuntimeRemovalPostconditionVerifier(compute, rabbit);

  @Test
  void confirmsAbsenceOnlyFromCurrentAdapterObservations() {
    when(compute.list()).thenReturn(List.of());
    when(rabbit.queue(io.pockethive.swarm.model.lifecycle.ResourcePlane.WORK, "queue-1")).thenReturn(Optional.empty());
    when(rabbit.exchange(io.pockethive.swarm.model.lifecycle.ResourcePlane.WORK, "exchange-1")).thenReturn(Optional.empty());
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
    when(rabbit.queue(io.pockethive.swarm.model.lifecycle.ResourcePlane.WORK, "queue-1")).thenReturn(Optional.of(new RabbitQueueResource("queue-1", 0, 0)));
    when(rabbit.exchange(io.pockethive.swarm.model.lifecycle.ResourcePlane.WORK, "exchange-1")).thenReturn(Optional.of(new RabbitExchangeResource("exchange-1")));
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

  private static RemoveResource resource(RemoveResourceType type, String id) {
    return new RemoveResource(type, id, type == RemoveResourceType.RABBIT_QUEUE || type == RemoveResourceType.RABBIT_EXCHANGE ? io.pockethive.swarm.model.lifecycle.ResourcePlane.WORK : io.pockethive.swarm.model.lifecycle.ResourcePlane.NONE);
  }
  @Test
  void absenceOnWorkDoesNotConfirmControlWithTheSameName() {
    var control = new RemoveResource(RemoveResourceType.RABBIT_QUEUE, "jobs", io.pockethive.swarm.model.lifecycle.ResourcePlane.CONTROL);
    var work = new RemoveResource(RemoveResourceType.RABBIT_QUEUE, "jobs", io.pockethive.swarm.model.lifecycle.ResourcePlane.WORK);
    when(rabbit.queue(control.plane(), "jobs")).thenReturn(Optional.of(new RabbitQueueResource("jobs", 0, 0)));
    when(rabbit.queue(work.plane(), "jobs")).thenReturn(Optional.empty());
    var result = verifier.verifyAbsent(List.of(control, work));
    assertThat(result.removedResources()).containsExactly(work);
    assertThat(result.remainingResources()).containsExactly(control);
    assertThat(result.succeeded()).isFalse();
  }

}
