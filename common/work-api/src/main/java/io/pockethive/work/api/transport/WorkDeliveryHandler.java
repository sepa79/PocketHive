package io.pockethive.work.api.transport;

import io.pockethive.work.api.WorkItem;

/**
 * Responsibility: accept decoded Work input or report an input decoding failure to the runtime.
 * Must not: settle broker messages or publish the result of an invocation.
 * Contract: RESP-WORK-TRANSPORT — docs/architecture/runtime-responsibilities.md#resp-work-transport.
 */
public interface WorkDeliveryHandler {
    void onWork(WorkItem item);
    void onDecodeFailure(byte[] body, Exception failure);
}
