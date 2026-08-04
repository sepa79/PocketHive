package io.pockethive.worker.sdk.api;

/** Raised when a WorkItem JSON envelope does not satisfy the canonical wire contract. */
public final class WorkItemContractException extends IllegalArgumentException {

    public WorkItemContractException(String message) {
        super(message);
    }

    public WorkItemContractException(String message, Throwable cause) {
        super(message, cause);
    }
}
