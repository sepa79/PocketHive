package io.pockethive.swarm.model.lifecycle;

public enum OperationType {
  CREATE,
  START,
  STOP,
  REMOVE,
  CONFIG_UPDATE;

  public boolean lifecycle() {
    return this != CONFIG_UPDATE;
  }
}
