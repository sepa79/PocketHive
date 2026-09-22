package io.pockethive.worker.sdk.runtime;

import io.pockethive.work.config.WorkDelivery;
import java.util.Objects;

/**
 * Responsibility: retain immutable startup IO bindings and their accepted delivery policy.
 * Must not: parse configuration, construct destinations or publish messages.
 * Contract: RESP-WORK-DELIVERY — docs/architecture/work-plane-boundaries.md#12-delayed-work-delivery.
 * Values are populated from the runtime
 * configuration (`pockethive.inputs/outputs.*`) which the control plane wires from the active swarm plan.
 */
public record WorkIoBindings(
    String inboundQueue,
    String outboundQueue,
    String outboundExchange,
    WorkDelivery outputDelivery
) {

    public static WorkIoBindings none() {
        return new WorkIoBindings(null, null, null, WorkDelivery.IMMEDIATE);
    }

    public static WorkIoBindings of(String inboundQueue, String outboundQueue, String outboundExchange) {
        return new WorkIoBindings(inboundQueue, outboundQueue, outboundExchange, WorkDelivery.IMMEDIATE);
    }

    public WorkIoBindings {
        Objects.requireNonNull(outputDelivery, "outputDelivery");
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
