package io.pockethive.artemis.transport;

import io.pockethive.artemis.api.ArtemisConnectionSettings;
import java.util.Objects;
import org.apache.activemq.artemis.api.core.ActiveMQException;
import org.apache.activemq.artemis.api.core.client.ActiveMQClient;
import org.apache.activemq.artemis.api.core.client.ClientSession;
import org.apache.activemq.artemis.api.core.client.ClientSessionFactory;
import org.apache.activemq.artemis.api.core.client.ServerLocator;

/**
 * Responsibility: own and close the Core connection factory and its session lifetime.
 * Must not: select adapter settings, reconstruct destinations or configure retries/failover.
 * Contract: RESP-ARTEMIS-CONNECTION — docs/architecture/runtime-responsibilities.md#resp-artemis-connection.
 */
public final class ArtemisSessions implements AutoCloseable {
    private final ArtemisConnectionSettings settings;
    private final ServerLocator locator;
    private final ClientSessionFactory factory;

    public ArtemisSessions(ArtemisConnectionSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        ServerLocator candidate = null;
        try {
            candidate = ActiveMQClient.createServerLocator(settings.brokerUrl());
            candidate.setCallTimeout(settings.callTimeoutMillis())
                .setReconnectAttempts(0).setInitialConnectAttempts(1)
                .setBlockOnDurableSend(false).setBlockOnNonDurableSend(false)
                .setBlockOnAcknowledge(false);
            factory = candidate.createSessionFactory();
            locator = candidate;
        } catch (Exception failure) {
            if (candidate != null) candidate.close();
            throw new IllegalStateException("Cannot open Artemis connection", failure);
        }
    }

    public String connectionIdentity() { return settings.identity(); }
    public long callTimeoutMillis() { return settings.callTimeoutMillis(); }

    public synchronized ClientSession open() {
        try {
            return factory.createSession(settings.username(), settings.password(), false, true, true, false, 0);
        } catch (ActiveMQException failure) {
            throw new IllegalStateException("Cannot open Artemis session", failure);
        }
    }

    @Override
    public synchronized void close() {
        try {
            factory.close();
        } finally {
            locator.close();
        }
    }
}
