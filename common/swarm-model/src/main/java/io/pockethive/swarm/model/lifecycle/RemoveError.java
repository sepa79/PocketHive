package io.pockethive.swarm.model.lifecycle;

public record RemoveError(String code, String message, RemoveResource resource) {

  public RemoveError {
    code = ContractValues.requireText("code", code);
    message = ContractValues.requireText("message", message);
  }
}
