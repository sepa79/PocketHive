package io.pockethive.orchestrator.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ShutdownSignalException;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;

class AmqpRabbitTopologyAdapterTest {

    @Test
    void onlyAmqpNotFoundShutdownSignalsAreTreatedAsMissingTopology() {
        ShutdownSignalException notFound = new ShutdownSignalException(
            false,
            false,
            new AMQP.Channel.Close.Builder()
                .replyCode(404)
                .replyText("NOT_FOUND - no exchange")
                .build(),
            null);
        ShutdownSignalException accessRefused = new ShutdownSignalException(
            false,
            false,
            new AMQP.Channel.Close.Builder()
                .replyCode(403)
                .replyText("ACCESS_REFUSED")
                .build(),
            null);

        assertThat(AmqpRabbitTopologyAdapter.isNotFound(new AmqpException("missing", notFound))).isTrue();
        assertThat(AmqpRabbitTopologyAdapter.isNotFound(new AmqpException("infra", accessRefused))).isFalse();
        assertThat(AmqpRabbitTopologyAdapter.isNotFound(new AmqpException("connection failed"))).isFalse();
    }
}
