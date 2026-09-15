package io.pockethive.artemis.work;

import io.pockethive.artemis.api.ArtemisOutputSettings;
import io.pockethive.artemis.transport.ArtemisSessions;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkItemJsonCodec;
import io.pockethive.work.api.transport.WorkOutput;
import java.util.Objects;
import org.apache.activemq.artemis.api.core.ActiveMQException;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.api.core.client.ClientProducer;
import org.apache.activemq.artemis.api.core.client.ClientSession;

/**
 * Responsibility: encode WorkItem results and serialize sends on a dedicated Core producer session.
 * Must not: reconstruct destinations, use an input session, dispatch workers or introduce delayed-delivery policy.
 * Contract: RESP-WORK-ARTEMIS-TRANSPORT — docs/architecture/runtime-responsibilities.md#resp-work-artemis-transport.
 */
public final class ArtemisWorkOutput implements WorkOutput {
    private final ArtemisOutputSettings settings;
    private final ClientSession session;
    private final ClientProducer producer;
    private final WorkItemJsonCodec codec = new WorkItemJsonCodec();

    public ArtemisWorkOutput(ArtemisSessions sessions, ArtemisOutputSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        session = Objects.requireNonNull(sessions, "sessions").open();
        try {
            producer = session.createProducer(SimpleString.of(settings.address()));
        } catch (ActiveMQException failure) {
            try {
                session.close();
            } catch (ActiveMQException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw new IllegalStateException("Cannot create Artemis Work output", failure);
        }
    }

    @Override
    public synchronized void publish(WorkItem item) {
        byte[] body = codec.toJson(item);
        try {
            var message = session.createMessage(settings.persistent());
            message.getBodyBuffer().writeBytes(body);
            producer.send(message);
        } catch (ActiveMQException failure) {
            throw new IllegalStateException("Cannot publish Artemis Work result", failure);
        }
    }
}
