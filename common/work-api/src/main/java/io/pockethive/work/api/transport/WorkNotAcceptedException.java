package io.pockethive.work.api.transport;

/**
 * Responsibility: report that a Work delivery was not submitted for execution.
 * Must not: represent failure of accepted work or encode a broker-specific settlement operation.
 * Contract: RESP-WORK-TRANSPORT — docs/architecture/runtime-responsibilities.md#resp-work-transport.
 */
public final class WorkNotAcceptedException extends RuntimeException {
    public WorkNotAcceptedException(String message) { super(message); }
    public WorkNotAcceptedException(String message, Throwable cause) { super(message, cause); }
}
