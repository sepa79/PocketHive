package io.pockethive.rabbit.work;

import io.pockethive.topology.work.WorkResourceIdentity;
/**
 * Responsibility: identify native Rabbit Work resources at the neutral resource boundary.
 * Must not: infer resource ownership or perform resource operations.
 * Contract: RESP-WORK-RESOURCE-NAMES — docs/architecture/runtime-responsibilities.md#resp-work-resource-names.
 */
public enum RabbitWorkResourceKind {
    EXCHANGE, QUEUE;

    private static final String OWNER = "rabbit";

    public WorkResourceIdentity identity(String name) {
        return new WorkResourceIdentity(OWNER, name(), name);
    }

    static RabbitWorkResourceKind require(WorkResourceIdentity resource) {
        if (!OWNER.equals(resource.owner())) throw new IllegalArgumentException("Resource does not belong to Rabbit WORK");
        return valueOf(resource.kind());
    }
}
