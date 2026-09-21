package io.pockethive.rabbit.api;


/**
 * Responsibility: expose explicit listener availability and running state.
 * Must not: represent worker desired state or claim lifecycle convergence.
 * Contract: RESP-WORK-RABBIT-POLICY — docs/architecture/runtime-responsibilities.md#resp-work-rabbit-policy.
 */
public enum RabbitListenerState { NOT_REGISTERED, STOPPED, RUNNING }
