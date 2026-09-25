package io.pockethive.rabbit.topology;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.rabbit.api.*;
import io.pockethive.rabbit.transport.SpringRabbitPublisher;
import io.pockethive.rabbit.transport.SpringRabbitReceiver;
import io.pockethive.rabbit.work.RabbitWorkResources;
import io.pockethive.rabbit.work.RabbitWorkTopologyResolver;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.containers.RabbitMQContainer;

/** Isolated adapter integration: no access to a deployed PocketHive service or broker. */
class SpringRabbitBrokerTest {
    private static final RabbitMQContainer BROKER = new RabbitMQContainer("rabbitmq:3.13.1-management");
    private final RabbitResourceNames names = new RabbitResourceNames();
    private CachingConnectionFactory connection;
    private SpringRabbitResources resources;
    private SpringRabbitPublisher publisher;
    private SpringRabbitReceiver receiver;

    @BeforeAll static void startBroker() { BROKER.start(); }
    @AfterAll static void stopBroker() { BROKER.stop(); }
    @BeforeEach void connect() {
        connection = new CachingConnectionFactory(BROKER.getHost(), BROKER.getAmqpPort());
        connection.setUsername(BROKER.getAdminUsername());
        connection.setPassword(BROKER.getAdminPassword());
        var template = new RabbitTemplate(connection);
        template.setReceiveTimeout(5000);
        resources = new SpringRabbitResources(new RabbitAdmin(connection));
        publisher = new SpringRabbitPublisher(template);
        receiver = new SpringRabbitReceiver(template);
    }
    @AfterEach void disconnect() { if (connection != null) connection.destroy(); }

    @Test void controlTransportDeliversUtf8ThroughTheDeclaredBinding() {
        String exchange = "control-contract-test";
        String route = "connectivity-probe";
        String queue = names.managerControlQueue("control-contract-test", "probe", "one");
        resources.declareExchange(new RabbitExchangeSpec(exchange, false, false, Map.of()));
        resources.declareQueue(new RabbitQueueSpec(queue, false, false, false, Map.of()));
        resources.bind(new RabbitBindingSpec(queue, exchange, route, Map.of()));
        publisher.sendText(exchange, route, "żółw");
        var delivered = receiver.receive(queue).orElseThrow();
        assertThat(delivered.body()).isEqualTo("żółw".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(delivered.receivedRoutingKey()).isEqualTo(route);
        assertThat(delivered.contentType()).isEqualTo(RabbitMessage.TEXT);
        assertThat(delivered.persistent()).isTrue();
        resources.deleteQueue(queue);
        resources.deleteExchange(exchange);
        assertThat(resources.queue(queue)).isEmpty();
        assertThat(resources.exchangeExists(exchange)).isFalse();
    }

    @Test void workBindingsHealAndRemovalPreservesTheOtherSwarm() {
        var resolver = new RabbitWorkTopologyResolver(names, names::forSwarm);
        var first = resolver.resolve("broker-first", Set.of("jobs"));
        var other = resolver.resolve("broker-other", Set.of("jobs"));
        var work = new RabbitWorkResources(resources, new RabbitConnectionSettings(
            BROKER.getHost(), BROKER.getAmqpPort(), BROKER.getAdminUsername(), BROKER.getAdminPassword(), "/"));
        work.ensure(first);
        work.ensure(other);
        work.ensure(first);
        var channel = first.channel("jobs");
        String exchange = names.forSwarm("broker-first").hiveExchange();
        publisher.sendText(exchange, channel.outputAddress(), "first");
        assertThat(receiver.receive(channel.inputAddress()).orElseThrow().body())
            .isEqualTo("first".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        resources.unbind(new RabbitBindingSpec(channel.inputAddress(), exchange, channel.outputAddress(), Map.of()));
        work.ensure(first);
        publisher.sendText(exchange, channel.outputAddress(), "healed");
        assertThat(receiver.receive(channel.inputAddress()).orElseThrow().body())
            .isEqualTo("healed".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for (var resource : first.resources().reversed()) {
            work.remove(resource);
            assertThat(work.observe(resource)).isEmpty();
        }
        other.resources().forEach(resource -> assertThat(work.observe(resource)).isPresent());
        publisher.sendText(names.forSwarm("broker-other").hiveExchange(), other.channel("jobs").outputAddress(), "survives");
        assertThat(receiver.receive(other.channel("jobs").inputAddress()).orElseThrow().body())
            .isEqualTo("survives".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for (var resource : other.resources().reversed()) {
            work.remove(resource);
            assertThat(work.observe(resource)).isEmpty();
        }
    }
}
