package io.pockethive.orchestrator.runtime;

import java.util.List;
import io.pockethive.swarm.model.lifecycle.RemoveResource;
import io.pockethive.swarm.model.lifecycle.RemoveError;

/**
 * Responsibility: retain the removal verifier's immutable result.
 * Must not: observe resources or independently infer removal effects.
 * Contract: docs/architecture/runtime-responsibilities.md.
 */
public record RuntimeRemovalVerification(
      List<RemoveResource> removedResources,
      List<RemoveResource> remainingResources,
      List<RemoveError> errors) {

    public RuntimeRemovalVerification {
      removedResources = List.copyOf(removedResources);
      remainingResources = List.copyOf(remainingResources);
      errors = List.copyOf(errors);
    }

    public boolean succeeded() {
      return remainingResources.isEmpty() && errors.isEmpty();
    }
}
