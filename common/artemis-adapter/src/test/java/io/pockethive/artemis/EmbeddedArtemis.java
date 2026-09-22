package io.pockethive.artemis;

import io.pockethive.artemis.api.ArtemisConnectionSettings;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.activemq.artemis.api.core.QueueConfiguration;
import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.api.core.client.ActiveMQClient;
import org.apache.activemq.artemis.core.config.CoreAddressConfiguration;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.ActiveMQServers;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;

/** Real Core broker scoped to one test, accessed only through its explicit in-process Core endpoint. */
final class EmbeddedArtemis implements AutoCloseable {
    static final String EXPIRY_QUEUE = "ExpiryQueue";
    private static final AtomicInteger IDS = new AtomicInteger();
    private final String brokerUrl = "vm://" + IDS.incrementAndGet();
    private final ActiveMQServer server;

    EmbeddedArtemis(Path directory) throws Exception {
        this(directory, true);
    }

    EmbeddedArtemis(Path directory, boolean startImmediately) throws Exception {
        var configuration = new ConfigurationImpl()
            .setPersistenceEnabled(false).setSecurityEnabled(false).setJMXManagementEnabled(false)
            .setThreadPoolMaxSize(4).setScheduledThreadPoolMaxSize(2)
            .setMessageExpiryScanPeriod(100)
            .setJournalDirectory(directory.resolve("journal").toString())
            .setBindingsDirectory(directory.resolve("bindings").toString())
            .setPagingDirectory(directory.resolve("paging").toString())
            .setLargeMessagesDirectory(directory.resolve("large-messages").toString())
            .addAcceptorConfiguration("test-core", brokerUrl)
            .addAddressConfiguration(new CoreAddressConfiguration().setName(EXPIRY_QUEUE)
                .addRoutingType(RoutingType.ANYCAST)
                .addQueueConfiguration(QueueConfiguration.of(EXPIRY_QUEUE).setRoutingType(RoutingType.ANYCAST)))
            .addAddressSetting("#", new AddressSettings().setAutoCreateAddresses(false).setAutoCreateQueues(false)
                .setAutoDeleteAddresses(false).setAutoDeleteQueues(false)
                .setExpiryAddress(SimpleString.of(EXPIRY_QUEUE)));
        server = ActiveMQServers.newActiveMQServer(configuration);
        if (startImmediately) start();
    }

    void start() throws Exception { server.start(); }

    ArtemisConnectionSettings settings() {
        return new ArtemisConnectionSettings(brokerUrl, "test-user", "test-password", 2000);
    }

    void sendRaw(String address, byte[] bytes) throws Exception {
        sendRaw(address, bytes, 0);
    }

    void sendRaw(String address, byte[] bytes, long expiration) throws Exception {
        try (var locator = ActiveMQClient.createServerLocator(brokerUrl);
             var factory = locator.createSessionFactory();
             var session = factory.createSession()) {
            var message = session.createMessage(false);
            message.setExpiration(expiration);
            message.getBodyBuffer().writeBytes(bytes);
            try (var producer = session.createProducer(address)) {
                producer.send(message);
            }
        }
    }

    @Override public void close() throws Exception { server.stop(); }
}
