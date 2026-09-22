package io.pockethive.artemis.broker;

import org.apache.activemq.artemis.api.core.Message;
import org.apache.activemq.artemis.core.server.transformer.Transformer;

/**
 * Responsibility: clear scheduled delivery on the independent message copy supplied by a diagnostic divert.
 * Must not: alter the source message, payload, expiry, routing or other properties, or open broker connections.
 * Contract: RESP-ARTEMIS-CAPTURE-TRANSFORM — docs/architecture/runtime-responsibilities.md#resp-artemis-capture-transform.
 */
public final class DiagnosticCaptureTransformer implements Transformer {
    @Override
    public Message transform(Message message) {
        message.setScheduledDeliveryTime(null);
        return message;
    }
}
