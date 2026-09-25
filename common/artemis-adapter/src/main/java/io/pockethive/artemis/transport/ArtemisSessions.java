package io.pockethive.artemis.transport;

import io.pockethive.artemis.api.ArtemisConnectionSettings;
import java.util.Objects;
import org.apache.activemq.artemis.api.core.client.ActiveMQClient;
import org.apache.activemq.artemis.api.core.client.ClientSession;
import org.apache.activemq.artemis.api.core.client.ClientSessionFactory;
import org.apache.activemq.artemis.api.core.client.ServerLocator;

/**
 * Responsibility: open the Core connection on first session use and own its factory/session lifetime.
 * Must not: connect during construction, reopen after close, reconstruct destinations or configure retries/failover.
 * Contract: RESP-ARTEMIS-CONNECTION — docs/architecture/runtime-responsibilities.md#resp-artemis-connection.
 */
public final class ArtemisSessions implements AutoCloseable {
    private final ArtemisConnectionSettings settings;
    private final ServerLocator locator;
    private ClientSessionFactory factory;
    private boolean closed;

    public ArtemisSessions(ArtemisConnectionSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        ServerLocator candidate = null;
        try {
            candidate = ActiveMQClient.createServerLocator(settings.brokerUrl());
            candidate.setCallTimeout(settings.callTimeoutMillis())
                .setReconnectAttempts(0).setInitialConnectAttempts(1)
                .setBlockOnDurableSend(false).setBlockOnNonDurableSend(false)
                .setBlockOnAcknowledge(false);
            locator = candidate;
        } catch (Exception failure) {
            if (candidate != null) candidate.close();
            throw new IllegalStateException("Cannot configure Artemis client", failure);
        }
    }

    public String connectionIdentity() { return settings.identity(); }
    public long callTimeoutMillis() { return settings.callTimeoutMillis(); }

    public synchronized ClientSession open() {
        if (closed) throw new IllegalStateException("Artemis connection owner is closed");
        try {
            if (factory == null) factory = locator.createSessionFactory();
            return factory.createSession(settings.username(), settings.password(), false, true, true, false, 0);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot open Artemis session", failure);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            if (factory != null) factory.close();
        } finally {
            locator.close();
        }
    }
}
