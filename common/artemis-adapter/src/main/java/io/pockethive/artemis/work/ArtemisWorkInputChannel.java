package io.pockethive.artemis.work;

import io.pockethive.artemis.api.ArtemisInputSettings;
import io.pockethive.artemis.transport.ArtemisSessions;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkItemJsonCodec;
import io.pockethive.work.api.transport.WorkDeliveryHandler;
import io.pockethive.work.api.transport.WorkInputChannel;
import io.pockethive.work.api.transport.WorkInputChannelState;
import io.pockethive.work.api.transport.WorkNotAcceptedException;
import java.util.Objects;
import org.apache.activemq.artemis.api.core.ActiveMQException;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.api.core.client.ClientConsumer;
import org.apache.activemq.artemis.api.core.client.ClientMessage;
import org.apache.activemq.artemis.api.core.client.ClientSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Responsibility: manage one Artemis subscription, project native state and settle each decoded Work delivery individually except explicitly unaccepted deliveries.
 * Must not: execute workers, await executor results, republish failures or merge broker headers into WorkItem.
 * Contract: RESP-WORK-ARTEMIS-TRANSPORT — docs/architecture/runtime-responsibilities.md#resp-work-artemis-transport.
 */
public final class ArtemisWorkInputChannel implements WorkInputChannel {
    private static final Logger log = LoggerFactory.getLogger(ArtemisWorkInputChannel.class);
    private final ArtemisSessions sessions;
    private final ArtemisInputSettings settings;
    private final WorkItemJsonCodec codec = new WorkItemJsonCodec();
    private volatile WorkDeliveryHandler handler;
    private volatile ClientConsumer consumer;
    private ClientSession session;

    public ArtemisWorkInputChannel(ArtemisSessions sessions, ArtemisInputSettings settings) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    public synchronized void register(WorkDeliveryHandler handler) {
        Objects.requireNonNull(handler, "handler");
        if (this.handler != null) {
            throw new IllegalStateException("Artemis input is already registered");
        }
        this.handler = handler;
    }

    @Override
    public WorkInputChannelState state() {
        if (handler == null) return WorkInputChannelState.NOT_REGISTERED;
        ClientConsumer current = consumer;
        return current != null && !current.isClosed()
            ? WorkInputChannelState.RUNNING : WorkInputChannelState.STOPPED;
    }

    @Override
    public synchronized void start() {
        if (handler == null) {
            throw new IllegalStateException("Artemis input must be registered before start");
        }
        if (state() == WorkInputChannelState.RUNNING) return;
        stop();
        ClientSession candidate = sessions.open();
        try {
            ClientConsumer subscription = candidate.createConsumer(SimpleString.of(settings.queue()),
                null, settings.consumerWindowBytes(), -1, false);
            subscription.setMessageHandler(this::deliver);
            candidate.start();
            session = candidate;
            consumer = subscription;
        } catch (Exception failure) {
            try {
                candidate.close();
            } catch (ActiveMQException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw new IllegalStateException("Cannot start Artemis Work input", failure);
        }
    }

    @Override
    public synchronized void stop() {
        if (session == null) return;
        try {
            session.close();
            session = null;
            consumer = null;
        } catch (ActiveMQException failure) {
            throw new IllegalStateException("Cannot stop Artemis Work input", failure);
        }
    }

    private void deliver(ClientMessage message) {
        byte[] body = new byte[0];
        boolean settle = true;
        try {
            WorkItem item;
            try {
                body = new byte[message.getBodyBuffer().readableBytes()];
                message.getBodyBuffer().readBytes(body);
                item = codec.fromJson(body);
            } catch (Exception failure) {
                handler.onDecodeFailure(body, failure);
                return;
            }
            handler.onWork(item);
        } catch (WorkNotAcceptedException notAccepted) {
            settle = false;
        } catch (RuntimeException failure) {
            log.warn("Artemis Work callback failed; delivery is consumed without requeue", failure);
        } finally {
            try {
                // A later consumed item must not acknowledge an earlier unaccepted delivery.
                if (settle) message.individualAcknowledge();
            } catch (ActiveMQException failure) {
                log.error("Cannot acknowledge Artemis Work delivery", failure);
            }
        }
    }
}
