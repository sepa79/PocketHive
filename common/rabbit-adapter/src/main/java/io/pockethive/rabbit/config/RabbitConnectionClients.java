package io.pockethive.rabbit.config;

import com.rabbitmq.client.ConnectionFactory;
import io.pockethive.rabbit.api.RabbitConnectionSettings;

/**
 * Responsibility: apply canonical connection values to the Rabbit client for either plane.
 * Must not: resolve settings, choose a plane or apply delivery policies.
 * Contract: RESP-RABBIT-CONNECTION — docs/architecture/runtime-responsibilities.md#resp-rabbit-connection.
 */
final class RabbitConnectionClients {
    private RabbitConnectionClients() { }

    static void configure(RabbitConnectionSettings settings, ConnectionFactory client) {
        client.setHost(settings.host());
        client.setPort(settings.port());
        client.setUsername(settings.username());
        client.setPassword(settings.password());
        client.setVirtualHost(settings.virtualHost());
    }
}
