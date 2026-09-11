package io.pockethive.swarm.model.lifecycle;

/**
 * Responsibility: identify the resource plane in lifecycle and cleanup contracts.
 * Must not: infer plane from names, choose connections or perform resource operations.
 * Contract: docs/spec/swarm-lifecycle.schema.json#/$defs/ResourcePlane.
 */
public enum ResourcePlane {
  CONTROL, WORK, NONE;

  public ResourcePlane requireRabbit() {
    if (this == NONE) throw new IllegalArgumentException("Rabbit resources require CONTROL or WORK plane");
    return this;
  }

  public void validate(RemoveResourceType type) {
    switch (type) {
      case RABBIT_QUEUE, RABBIT_EXCHANGE, RABBIT_BINDING -> requireRabbit();
      default -> {
        if (this != NONE) throw new IllegalArgumentException("Non-messaging resources require NONE plane");
      }
    }
  }
}
