package io.pockethive.work.api;

/**
 * Declares high-level worker capabilities so downstream systems (scenario-manager, UI, etc.)
 * can reason about behaviour without manual wiring.
 * <p>
 * Responsibility: define the WorkerCapability contract.
 * Must not: configure transport clients or own adapter lifecycle.
 * Contract: RESP-WORK-CAPABILITY — docs/architecture/runtime-responsibilities.md#resp-work-capability.
 */
public enum WorkerCapability {

    /**
     * Worker pulls synthetic work according to a schedule (generator, trigger, etc.).
     */
    SCHEDULER,

    /**
     * Worker consumes messages from a queue or stream (moderator, processor, ...).
     */
    MESSAGE_DRIVEN,

    /**
     * Worker performs HTTP interactions (webhook style, REST pollers, etc.).
     */
    HTTP,

    /**
     * Worker maintains long-lived streaming connections (websockets, SSE, etc.).
     */
    STREAMING
}
