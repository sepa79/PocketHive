package io.pockethive.swarm.model.lifecycle;

public record Target(String role, String instance) {

  public Target {
    role = ContractValues.requireText("role", role);
    instance = ContractValues.requireText("instance", instance);
  }
}
