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

    private final ObjectMapper mapper = new ObjectMapper();

    private String host = "localhost";
    private int port = 5672;
    private String username = "guest";
    private String password = "guest";
    private String exchange = "logs.exchange";
    private boolean enabled = true;

    private Connection connection;
    private Channel channel;

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

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public void start() {
        if (!enabled) {
            addInfo("RabbitMqLogAppender disabled by configuration");
            return;
        }
        try {
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost(host);
            factory.setPort(port);
            factory.setUsername(username);
            factory.setPassword(password);
            connection = factory.newConnection();
            channel = connection.createChannel();
            super.start();
        } catch (Exception e) {
            addError("Failed to start RabbitMqLogAppender", e);
        }
    }

    @Override
    protected void append(ILoggingEvent eventObject) {
        if (!isStarted()) {
            return;
        }
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("timestamp", eventObject.getTimeStamp());
            payload.put("level", eventObject.getLevel().toString());
            payload.put("logger", eventObject.getLoggerName());
            payload.put("thread", eventObject.getThreadName());
            payload.put("message", eventObject.getFormattedMessage());
            if (eventObject.getThrowableProxy() != null) {
                payload.put("exception", eventObject.getThrowableProxy().getMessage());
            }
            if (eventObject.getMDCPropertyMap() != null && !eventObject.getMDCPropertyMap().isEmpty()) {
                payload.put("mdc", eventObject.getMDCPropertyMap());
            }
            byte[] body = mapper.writeValueAsBytes(payload);
            channel.basicPublish(exchange, "", null, body);
        } catch (Exception e) {
            addError("Unable to publish log event", e);
        }
    }

    @Override
    public void stop() {
        try {
            if (channel != null) {
                channel.close();
            }
            if (connection != null) {
                connection.close();
            }
        } catch (IOException | TimeoutException e) {
            addError("Failed to close RabbitMQ resources", e);
        }
        super.stop();
    }
}
