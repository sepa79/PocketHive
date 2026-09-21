package io.pockethive.work.api.transport;

/**
 * Responsibility: expose the lifecycle of one explicitly configured incoming Work channel.
 * Must not: own worker state, execution policy, result publication or adapter selection.
 * Contract: RESP-WORK-TRANSPORT — docs/architecture/runtime-responsibilities.md#resp-work-transport.
 */
public interface WorkInputChannel {
    void register(WorkDeliveryHandler handler);
    WorkInputChannelState state();
    void start();
    void stop();
}
