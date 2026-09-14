package io.pockethive.orchestrator.runtime;

import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.ComputeRuntimeInventoryPort;
import io.pockethive.orchestrator.runtime.RabbitTopologyPort;
import io.pockethive.swarm.model.lifecycle.RemoveError;
import io.pockethive.swarm.model.lifecycle.RemoveResource;
import io.pockethive.swarm.model.lifecycle.RemoveResourceType;
import io.pockethive.swarm.model.lifecycle.ResourcePlane;
import io.pockethive.topology.work.WorkPlaneResources;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Responsibility: verify absence through compute, CONTROL and selected WORK resource owners.
 * Must not: infer success from attempted deletion or inspect a different resource plane.
 * Contract: RESP-RUNTIME-CLEANUP — docs/architecture/runtime-responsibilities.md#resp-runtime-cleanup.
 */
@Service
public final class RuntimeRemovalPostconditionVerifier {

  private final ComputeRuntimeInventoryPort computeInventory;
  private final RabbitTopologyPort rabbitTopology;
  private final WorkPlaneResources workResources;

  public RuntimeRemovalPostconditionVerifier(
      ComputeRuntimeInventoryPort computeInventory,
      RabbitTopologyPort rabbitTopology,
      WorkPlaneResources workResources) {
    this.computeInventory = Objects.requireNonNull(computeInventory, "computeInventory");
    this.rabbitTopology = Objects.requireNonNull(rabbitTopology, "rabbitTopology");
    this.workResources = Objects.requireNonNull(workResources, "workResources");
  }

  public RuntimeRemovalVerification verifyAbsent(List<RemoveResource> targets) {
    Objects.requireNonNull(targets, "targets");
    List<RemoveResource> uniqueTargets = List.copyOf(new LinkedHashSet<>(targets));
    Set<String> runtimeIds = runtimeIds(uniqueTargets);
    RuntimeObservation runtimeObservation = observeRuntime(runtimeIds);
    List<RemoveResource> removed = new ArrayList<>();
    List<RemoveResource> remaining = new ArrayList<>();
    List<RemoveError> errors = new ArrayList<>();

    for (RemoveResource target : uniqueTargets) {
      try {
        boolean present = target.plane() == ResourcePlane.WORK
            ? workResources.observe(workResources.identify(target)).isPresent()
            : switch (target.type()) {
          case CONTROLLER_RUNTIME, WORKER_RUNTIME -> {
            if (runtimeObservation.failure() != null) {
              throw runtimeObservation.failure();
            }
            yield runtimeObservation.presentRuntimeIds().contains(target.id());
          }
          case RABBIT_QUEUE -> rabbitTopology.queue(target.plane(), target.id()).isPresent();
          case RABBIT_EXCHANGE -> rabbitTopology.exchange(target.plane(), target.id()).isPresent();
          case RABBIT_BINDING -> throw new IllegalArgumentException(
              "Rabbit binding absence is not observable by the configured topology port");
          case WORK_RESOURCE -> throw new IllegalArgumentException("Work resources require WORK plane");
          case NETWORK_BINDING, RUNTIME_DIRECTORY, REGISTRY_ENTRY, TERMINAL_EVIDENCE -> throw new IllegalArgumentException(
              target.type() + " belongs to a later remove postcondition stage");
        };
        if (present) {
          remaining.add(target);
          errors.add(new RemoveError(
              "RESOURCE_STILL_PRESENT",
              "Resource is still present after remove action",
              target));
        } else {
          removed.add(target);
        }
      } catch (RuntimeException failure) {
        remaining.add(target);
        errors.add(new RemoveError(
            failure.getClass().getSimpleName(),
            Objects.toString(failure.getMessage(), failure.getClass().getName()),
            target));
      }
    }
    return new RuntimeRemovalVerification(removed, remaining, errors);
  }

  private RuntimeObservation observeRuntime(Set<String> targetIds) {
    if (targetIds.isEmpty()) {
      return new RuntimeObservation(Set.of(), null);
    }
    try {
      Set<String> observed = new LinkedHashSet<>();
      computeInventory.list().stream()
          .map(RuntimeCleanupPorts.ComputeRuntimeResource::runtimeId)
          .filter(Objects::nonNull)
          .filter(targetIds::contains)
          .forEach(observed::add);
      return new RuntimeObservation(Set.copyOf(observed), null);
    } catch (RuntimeException failure) {
      return new RuntimeObservation(Set.of(), failure);
    }
  }

  private static Set<String> runtimeIds(List<RemoveResource> targets) {
    Set<String> ids = new LinkedHashSet<>();
    targets.stream()
        .filter(target -> target.type() == RemoveResourceType.CONTROLLER_RUNTIME
            || target.type() == RemoveResourceType.WORKER_RUNTIME)
        .map(RemoveResource::id)
        .forEach(ids::add);
    return Set.copyOf(ids);
  }

  private record RuntimeObservation(Set<String> presentRuntimeIds, RuntimeException failure) {
  }

}
