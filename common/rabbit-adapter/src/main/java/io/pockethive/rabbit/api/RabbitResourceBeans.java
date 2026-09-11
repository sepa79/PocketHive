package io.pockethive.rabbit.api;
/**
 * Responsibility: name explicit resource capabilities for Control and Work composition.
 * Must not: choose a connection or provide an unscoped resource capability.
 * Contract: docs/architecture/work-plane-boundaries.md#4-configuration-and-topology-ssot.
 */
public final class RabbitResourceBeans {
    public static final String CONTROL = "rabbitControlResources";
    public static final String WORK = "rabbitWorkResources";
    private RabbitResourceBeans() { }
}
