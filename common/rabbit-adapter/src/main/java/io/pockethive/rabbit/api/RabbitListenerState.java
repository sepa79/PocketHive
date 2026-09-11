package io.pockethive.rabbit.api;


/**
 * Responsibility: expose explicit listener availability and running state.
 * Must not: represent worker desired state or claim lifecycle convergence.
 * Contract: docs/architecture/work-plane-boundaries.md#3-ports-owners-and-state-transitions.
 */
public enum RabbitListenerState { NOT_REGISTERED, STOPPED, RUNNING }
