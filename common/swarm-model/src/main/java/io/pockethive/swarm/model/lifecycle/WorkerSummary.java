package io.pockethive.swarm.model.lifecycle;

public record WorkerSummary(String role, String instance, String image) {

  public WorkerSummary {
    role = ContractValues.requireText("role", role);
    instance = ContractValues.requireText("instance", instance);
    image = ContractValues.optionalText(image);
  }
}
