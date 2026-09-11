package io.pockethive.swarm.model.lifecycle;

import java.util.Objects;

/**
 * Responsibility: identify one removal target by type, name and explicit plane.
 * Must not: infer a missing plane or decide whether removal succeeded.
 * Contract: docs/spec/swarm-lifecycle.schema.json#/$defs/RemoveResource.
 */
public record RemoveResource(RemoveResourceType type, String id, ResourcePlane plane) {

  public RemoveResource {
    type = Objects.requireNonNull(type, "type");
    id = ContractValues.requireText("id", id);
    Objects.requireNonNull(plane, "plane").validate(type);
  }
}
