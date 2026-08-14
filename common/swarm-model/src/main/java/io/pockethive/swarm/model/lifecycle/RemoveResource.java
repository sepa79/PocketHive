package io.pockethive.swarm.model.lifecycle;

import java.util.Objects;

public record RemoveResource(RemoveResourceType type, String id) {

  public RemoveResource {
    type = Objects.requireNonNull(type, "type");
    id = ContractValues.requireText("id", id);
  }
}
