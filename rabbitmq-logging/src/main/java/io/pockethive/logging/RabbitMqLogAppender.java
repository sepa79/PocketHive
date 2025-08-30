package io.pockethive.logging;

import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * A Logback appender that publishes log events as JSON messages to a RabbitMQ exchange.
 */
public class RabbitMqLogAppender extends AppenderBase<ILoggingEvent> {
    private String host = "localhost";
    private int port = 5672;
    private String username = "guest";
    private String password = "guest";
    private String exchange = "logs.exchange";

    private Connection connection;
    private Channel channel;
    private final ObjectMapper mapper = new ObjectMapper();

    public void setHost(String host) {
        this.host = host;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public void setExchange(String exchange) {
        this.exchange = exchange;
    }

    @Override
    public void start() {
        try {
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost(host);
            factory.setPort(port);
            factory.setUsername(username);
            factory.setPassword(password);
            connection = factory.newConnection();
            channel = connection.createChannel();
            channel.exchangeDeclare(exchange, "fanout", true);
            super.start();
        } catch (IOException | TimeoutException e) {
            addError("Failed to start RabbitMqLogAppender", e);
        }
    }

    @Override
    protected void append(ILoggingEvent eventObject) {
        if (!isStarted() || channel == null) {
            return;
        }
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("timestamp", eventObject.getTimeStamp());
            payload.put("level", eventObject.getLevel().toString());
            payload.put("logger", eventObject.getLoggerName());
            payload.put("message", eventObject.getFormattedMessage());
            if (eventObject.getMDCPropertyMap() != null && !eventObject.getMDCPropertyMap().isEmpty()) {
                payload.put("mdc", eventObject.getMDCPropertyMap());
            }
            byte[] body = mapper.writeValueAsBytes(payload);
            channel.basicPublish(exchange, "", null, body);
        } catch (IOException e) {
            addError("Failed to publish log event", e);
        }
    }

    @Override
    public void stop() {
        try {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
            if (connection != null && connection.isOpen()) {
                connection.close();
            }
        } catch (Exception e) {
            addError("Failed to close RabbitMQ resources", e);
        } finally {
            super.stop();
        }
    }
}

