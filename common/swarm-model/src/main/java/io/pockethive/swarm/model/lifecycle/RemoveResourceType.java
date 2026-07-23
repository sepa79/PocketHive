package io.pockethive.swarm.model.lifecycle;

public enum RemoveResourceType {
  CONTROLLER_RUNTIME,
  WORKER_RUNTIME,
  RABBIT_QUEUE,
  RABBIT_EXCHANGE,
  RABBIT_BINDING,
  RUNTIME_DIRECTORY,
  REGISTRY_ENTRY,
  TERMINAL_EVIDENCE
}
