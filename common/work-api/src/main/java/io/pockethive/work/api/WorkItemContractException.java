package io.pockethive.work.api;

/** Raised when a WorkItem JSON envelope does not satisfy the canonical wire contract. * <p>
 * Responsibility: define the WorkItemContractException contract.
 * Must not: configure transport clients or own adapter lifecycle.
 * Contract: RESP-WORK-WIRE — docs/architecture/runtime-responsibilities.md#resp-work-wire.
 */
public final class WorkItemContractException extends IllegalArgumentException {

    public WorkItemContractException(String message) {
        super(message);
    }

    public WorkItemContractException(String message, Throwable cause) {
        super(message, cause);
    }
}
