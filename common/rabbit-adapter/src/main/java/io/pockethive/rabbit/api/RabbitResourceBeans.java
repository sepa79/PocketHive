package io.pockethive.rabbit.api;
/**
 * Responsibility: name explicit resource capabilities for Control and Work composition.
 * Must not: choose a connection or provide an unscoped resource capability.
 * Contract: RESP-RABBIT-RESOURCES — docs/architecture/runtime-responsibilities.md#resp-rabbit-resources.
 */
public final class RabbitResourceBeans {
    public static final String CONTROL = "rabbitControlResources";
    public static final String WORK = "rabbitWorkResources";
    private RabbitResourceBeans() { }
}
