package io.pockethive.rabbit.api;

/**
 * Responsibility: name the plane-specific transport capabilities used by composition.
 * Must not: configure clients or infer plane from domain routes.
 * Contract: RESP-RABBIT-TRANSPORT — docs/architecture/runtime-responsibilities.md#resp-rabbit-transport.
 */
public final class RabbitTransportBeans {
    public static final String CONTROL_PUBLISHER = "rabbitControlPublisher";
    public static final String WORK_PUBLISHER = "rabbitWorkPublisher";
    private RabbitTransportBeans() { }
}
