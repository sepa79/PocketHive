package io.pockethive.controlplane.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.pockethive.control.CommandState;
import io.pockethive.control.ConfirmationScope;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.messaging.SignalMessage;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ControlPlanePublisherIntegrationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(ControlPlaneCommonAutoConfiguration.class)
        .withPropertyValues("pockethive.control-plane.exchange=ph.integration");

    @Test
    void publisherSendsSignalsAndEventsToConfiguredExchange() {
        contextRunner.withBean(AmqpTemplate.class, () -> mock(AmqpTemplate.class)).run(context -> {
            ControlPlanePublisher publisher = context.getBean(ControlPlanePublisher.class);
            ControlPlaneProperties properties = context.getBean(ControlPlaneProperties.class);
            AmqpTemplate template = context.getBean(AmqpTemplate.class);

            ControlPlaneIdentity identity = new ControlPlaneIdentity("swarm-A", "generator", "gen-1");
            ControlPlaneEmitter emitter = ControlPlaneEmitter.generator(identity, publisher);
            ConfirmationScope scope = new ConfirmationScope("swarm-A", "generator", "gen-1");

            ControlPlaneEmitter.ReadyContext ready = ControlPlaneEmitter.ReadyContext.builder(
                    "swarm-start",
                    "corr-1",
                    "idem-1",
                    CommandState.status("Running"))
                .timestamp(Instant.parse("2024-01-01T00:00:00Z"))
                .build();
            emitter.emitReady(ready);

            ControlSignal signal = ControlSignal.forInstance(
                "config-update",
                "swarm-A",
                "generator",
                "gen-1",
                "corr-2",
                "idem-2");
            String signalKey = ControlPlaneRouting.signal("config-update", "swarm-A", "generator", "gen-1");
            publisher.publishSignal(new SignalMessage(signalKey, signal));

            ArgumentCaptor<String> routingCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
            verify(template, times(2)).convertAndSend(eq(properties.getExchange()), routingCaptor.capture(), payloadCaptor.capture());

            List<String> routes = routingCaptor.getAllValues();
            assertThat(routes).contains(signalKey,
                ControlPlaneRouting.event("ready", "swarm-start", scope));
            assertThat(payloadCaptor.getAllValues()).allSatisfy(payload -> assertThat(payload).isNotNull());
        });
    }
}
