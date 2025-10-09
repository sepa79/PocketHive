package io.pockethive.worker.sdk.api;

/**
 * Defines the minimal business contract for generator-style workers.
 * <p>
 * Generator workers are invoked on a schedule managed by the runtime. See the
 * {@code docs/sdk/worker-sdk-quickstart.md} guide for usage examples and Stage 1–3
 * behaviour notes.
 */
@FunctionalInterface
public interface GeneratorWorker {

    /**
     * Generates the next message to send downstream.
     *
     * @param context runtime context populated with control-plane configuration, status publisher,
     *                logging, and observability hooks
     * @return a {@link WorkResult#message(WorkMessage)} to emit or {@link WorkResult#none()} when
     *         no message should be produced
     * @throws Exception any exception raised during generation. The runtime records the failure and
     *                   emits status updates according to the quick start guide.
     */
    WorkResult generate(WorkerContext context) throws Exception;
}
