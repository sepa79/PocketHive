package io.pockethive.rabbit.config;

import io.pockethive.rabbit.api.RabbitConnectionSettings;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.DisposableBean;

/**
 * Responsibility: own the Work connection and template configured from canonical settings.
 * Must not: inherit Control settings, register Control declarations or expose clients outside this module.
 * Contract: RESP-RABBIT-CONNECTION — docs/architecture/runtime-responsibilities.md#resp-rabbit-connection.
 */
public final class WorkRabbitConnection implements DisposableBean {
    private final CachingConnectionFactory connection;
    private final RabbitTemplate template;

    public WorkRabbitConnection(RabbitConnectionSettings settings) {
        var client = new com.rabbitmq.client.ConnectionFactory();
        client.setAutomaticRecoveryEnabled(false);
        RabbitConnectionClients.configure(settings, client);
        connection = new CachingConnectionFactory(client);
        template = new RabbitTemplate(connection);
    }

    public ConnectionFactory connection() { return connection; }
    public RabbitTemplate template() { return template; }
    @Override public void destroy() { connection.destroy(); }
}
