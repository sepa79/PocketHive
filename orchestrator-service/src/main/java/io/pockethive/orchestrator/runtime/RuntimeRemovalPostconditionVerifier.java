package io.pockethive.orchestrator.runtime;

import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.ComputeRuntimeInventoryPort;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.RabbitTopologyPort;
import io.pockethive.swarm.model.lifecycle.RemoveError;
import io.pockethive.swarm.model.lifecycle.RemoveResource;
import io.pockethive.swarm.model.lifecycle.RemoveResourceType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Sole evaluator of runtime and RabbitMQ absence postconditions for swarm removal. */
@Service
public final class RuntimeRemovalPostconditionVerifier {

  private final ComputeRuntimeInventoryPort computeInventory;
  private final RabbitTopologyPort rabbitTopology;

  public RuntimeRemovalPostconditionVerifier(
      ComputeRuntimeInventoryPort computeInventory,
      RabbitTopologyPort rabbitTopology) {
    this.computeInventory = Objects.requireNonNull(computeInventory, "computeInventory");
    this.rabbitTopology = Objects.requireNonNull(rabbitTopology, "rabbitTopology");
  }

  public Verification verifyAbsent(List<RemoveResource> targets) {
    Objects.requireNonNull(targets, "targets");
    List<RemoveResource> uniqueTargets = List.copyOf(new LinkedHashSet<>(targets));
    Set<String> runtimeIds = runtimeIds(uniqueTargets);
    RuntimeObservation runtimeObservation = observeRuntime(runtimeIds);
    List<RemoveResource> removed = new ArrayList<>();
    List<RemoveResource> remaining = new ArrayList<>();
    List<RemoveError> errors = new ArrayList<>();

    for (RemoveResource target : uniqueTargets) {
      try {
        boolean present = switch (target.type()) {
          case CONTROLLER_RUNTIME, WORKER_RUNTIME -> {
            if (runtimeObservation.failure() != null) {
              throw runtimeObservation.failure();
            }
            yield runtimeObservation.presentRuntimeIds().contains(target.id());
          }
          case RABBIT_QUEUE -> rabbitTopology.queue(target.id()).isPresent();
          case RABBIT_EXCHANGE -> rabbitTopology.exchange(target.id()).isPresent();
          case RABBIT_BINDING -> throw new IllegalArgumentException(
              "Rabbit binding absence is not observable by the configured topology port");
          case RUNTIME_DIRECTORY, REGISTRY_ENTRY, TERMINAL_EVIDENCE -> throw new IllegalArgumentException(
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
    return new Verification(removed, remaining, errors);
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

  public record Verification(
      List<RemoveResource> removedResources,
      List<RemoveResource> remainingResources,
      List<RemoveError> errors) {

    public Verification {
      removedResources = List.copyOf(removedResources);
      remainingResources = List.copyOf(remainingResources);
      errors = List.copyOf(errors);
    }

    public boolean succeeded() {
      return remainingResources.isEmpty() && errors.isEmpty();
    }
  }
}
