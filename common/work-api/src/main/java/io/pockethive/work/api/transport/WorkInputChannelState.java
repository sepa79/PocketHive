package io.pockethive.work.api.transport;

/**
 * Responsibility: report the registered input channel lifecycle state.
 * Must not: infer worker enablement or domain operation success.
 * Contract: RESP-WORK-TRANSPORT — docs/architecture/runtime-responsibilities.md#resp-work-transport.
 */
public enum WorkInputChannelState { NOT_REGISTERED, STOPPED, RUNNING }
