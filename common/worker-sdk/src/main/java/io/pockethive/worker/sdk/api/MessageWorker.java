package io.pockethive.worker.sdk.api;

/**
 * Defines the minimal business contract for message-processing workers.
 * <p>
 * Message workers are invoked when transports deliver inbound work messages. Consult the
 * {@code docs/sdk/worker-sdk-quickstart.md} guide for lifecycle, status, and observability
 * conventions.
 */
@FunctionalInterface
public interface MessageWorker {

    /**
     * Processes an inbound message and optionally emits a new payload.
     *
     * @param in      inbound payload converted to a {@link WorkMessage}
     * @param context runtime context populated with control-plane configuration, status publisher,
     *                logging, and observability hooks
     * @return {@link WorkResult#message(WorkMessage)} to publish downstream or
     *         {@link WorkResult#none()} when no response is required
     * @throws Exception any exception raised while handling the message. The runtime records the
     *                   failure and emits status updates according to the quick start guide.
     */
    WorkResult onMessage(WorkMessage in, WorkerContext context) throws Exception;
}
