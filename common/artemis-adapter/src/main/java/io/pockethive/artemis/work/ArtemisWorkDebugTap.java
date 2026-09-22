package io.pockethive.artemis.work;

import io.pockethive.artemis.transport.ArtemisManagement;
import io.pockethive.artemis.transport.ArtemisSessions;
import io.pockethive.topology.work.WorkDebugTap;
import java.util.Optional;
import org.apache.activemq.artemis.api.core.QueueConfiguration;
import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.api.core.client.ClientConsumer;
import org.apache.activemq.artemis.api.core.client.ClientSession;
import org.apache.activemq.artemis.api.core.management.ResourceNames;

/**
 * Responsibility: own a bounded Artemis diagnostic capture, its reads and explicit resource release.
 * Must not: consume or change expiry policy of the source Work queue, retain UI samples or decide request lifetime.
 * Contract: RESP-WORK-ARTEMIS-TRANSPORT — docs/architecture/runtime-responsibilities.md#resp-work-artemis-transport.
 */
final class ArtemisWorkDebugTap implements WorkDebugTap {
    private static final String ADD_SETTINGS = "addAddressSettings";
    private static final String REMOVE_SETTINGS = "removeAddressSettings";
    private static final String CREATE_DIVERT = "createDivert";
    private static final String DESTROY_DIVERT = "destroyDivert";
    private static final String CAPTURE_TRANSFORMER = "io.pockethive.artemis.broker.DiagnosticCaptureTransformer";
    private final String source;
    private final String capture;
    private final String divert;
    private final ClientSession session;
    private final ArtemisManagement management;
    private ClientConsumer consumer;
    private boolean addressCreated;
    private boolean queueCreated;
    private boolean settingsCreated;
    private boolean divertCreated;
    private boolean closed;

    ArtemisWorkDebugTap(ArtemisSessions sessions, String source, String capture, String divert, int ttlSeconds, int maxItems) {
        this.source = source;
        this.capture = capture;
        this.divert = divert;
        session = sessions.open();
        management = new ArtemisManagement(session, sessions.callTimeoutMillis());
        try {
            if (!session.addressQuery(SimpleString.of(source)).isExists()) {
                throw new IllegalArgumentException("Artemis capture source is absent");
            }
            session.createAddress(SimpleString.of(capture), RoutingType.ANYCAST, false);
            addressCreated = true;
            // Exact-address rule applies only to the diagnostic copy, never the source.
            // Artemis treats an empty expiry address as discard; null would inherit the wildcard address.
            management.invoke(ResourceNames.BROKER, ADD_SETTINGS, capture,
                "{\"expiryDelay\":" + ttlSeconds * 1000L + ",\"expiryAddress\":\"\",\"autoCreateQueues\":false,\"autoCreateAddresses\":false}");
            settingsCreated = true;
            session.createQueue(QueueConfiguration.of(capture).setAddress(capture).setRoutingType(RoutingType.ANYCAST)
                .setDurable(false).setTemporary(true).setAutoDelete(false).setRingSize((long) maxItems));
            queueCreated = true;
            // Scheduled copies must enter the ring immediately; the broker transforms only the divert's copy.
            management.invoke(ResourceNames.BROKER, CREATE_DIVERT, divert, divert, source, capture,
                false, null, CAPTURE_TRANSFORMER, RoutingType.ANYCAST.name());
            divertCreated = true;
            consumer = session.createConsumer(capture, null, 0, -1, false);
            session.start();
        } catch (Exception failure) {
            try { close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw new IllegalStateException("Cannot open Artemis diagnostic capture", failure);
        }
    }

    @Override public String sourceGroup() { return source; }
    @Override public String sourceAddress() { return source; }
    @Override public String captureAddress() { return capture; }

    @Override public synchronized Optional<byte[]> receive() {
        if (closed) throw new IllegalStateException("Artemis capture is closed");
        try {
            var message = consumer.receiveImmediate();
            if (message == null) return Optional.empty();
            byte[] body = new byte[message.getBodyBuffer().readableBytes()];
            message.getBodyBuffer().readBytes(body);
            message.individualAcknowledge();
            return Optional.of(body);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot read Artemis diagnostic capture", failure);
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        var failures = new java.util.ArrayList<Exception>();
        if (divertCreated) release(failures, () -> management.invoke(ResourceNames.BROKER, DESTROY_DIVERT, divert));
        if (consumer != null) release(failures, consumer::close);
        if (queueCreated) release(failures, () -> session.deleteQueue(capture));
        if (addressCreated) release(failures, () -> management.deleteAddress(capture));
        if (settingsCreated) release(failures, () -> management.invoke(ResourceNames.BROKER, REMOVE_SETTINGS, capture));
        release(failures, session::close);
        if (!failures.isEmpty()) {
            var failure = new IllegalStateException("Cannot release Artemis diagnostic capture", failures.getFirst());
            failures.stream().skip(1).forEach(failure::addSuppressed);
            throw failure;
        }
    }

    private static void release(java.util.List<Exception> failures, AutoCloseable action) {
        try { action.close(); } catch (Exception failure) { failures.add(failure); }
    }
}
