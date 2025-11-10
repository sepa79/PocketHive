package io.pockethive.worker.sdk.runtime;

/**
 * Describes the work-queue bindings resolved for a worker. Values are populated from the runtime
 * configuration (`pockethive.inputs/outputs.*`) which the control plane wires from the active swarm plan.
 */
public record WorkIoBindings(
    String inboundQueue,
    String outboundQueue,
    String outboundExchange
) {

    public static WorkIoBindings none() {
        return new WorkIoBindings(null, null, null);
    }

    public static WorkIoBindings of(String inboundQueue, String outboundQueue, String outboundExchange) {
        return new WorkIoBindings(inboundQueue, outboundQueue, outboundExchange);
    }

    public WorkIoBindings {
        inboundQueue = normalise(inboundQueue);
        outboundQueue = normalise(outboundQueue);
        outboundExchange = normalise(outboundExchange);
    }

    public boolean hasInboundQueue() {
        return inboundQueue != null;
    }

    public boolean hasOutboundQueue() {
        return outboundQueue != null;
    }

    public boolean hasOutboundExchange() {
        return outboundExchange != null;
    }

    private static String normalise(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
