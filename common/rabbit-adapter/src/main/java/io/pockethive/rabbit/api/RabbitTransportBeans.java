package io.pockethive.rabbit.api;

/**
 * Responsibility: name the plane-specific transport capabilities used by composition.
 * Must not: configure clients or infer plane from domain routes.
 * Contract: docs/architecture/work-plane-boundaries.md#connection-split-prerequisite-resource-identity.
 */
public final class RabbitTransportBeans {
    public static final String CONTROL_PUBLISHER = "rabbitControlPublisher";
    public static final String WORK_PUBLISHER = "rabbitWorkPublisher";
    private RabbitTransportBeans() { }
}
