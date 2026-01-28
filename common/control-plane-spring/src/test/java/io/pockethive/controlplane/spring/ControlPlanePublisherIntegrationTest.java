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
import io.pockethive.controlplane.topology.ControlPlaneTopologySettings;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.amqp.core.TopicExchange;

class ControlPlanePublisherIntegrationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(ControlPlaneCommonAutoConfiguration.class)
        .withPropertyValues(
            "pockethive.control-plane.worker.enabled=false",
            "pockethive.control-plane.instance-id=integration-test",
            "pockethive.control-plane.swarm-id=integration-swarm",
            "pockethive.control-plane.control-queue-prefix=ph.control.integration");

    @Test
    void publisherSendsSignalsAndEventsToConfiguredExchange() {
        contextRunner
            .withPropertyValues("pockethive.control-plane.exchange=ph.integration")
            .withBean(RabbitTemplate.class, () -> mock(RabbitTemplate.class))
            .run(context -> {
            ControlPlanePublisher publisher = context.getBean(ControlPlanePublisher.class);
            RabbitTemplate template = context.getBean(RabbitTemplate.class);
            TopicExchange exchange = context.getBean("controlPlaneExchange", TopicExchange.class);

            ControlPlaneIdentity identity = new ControlPlaneIdentity("swarm-A", "generator", "gen-1");
            ControlPlaneProperties properties = context.getBean(ControlPlaneProperties.class);
            ControlPlaneTopologySettings settings = new ControlPlaneTopologySettings(
                properties.getSwarmId(), properties.getControlQueuePrefix(), Map.of());
            Map<String, Object> runtime = Map.of("templateId", "tpl-1", "runId", "run-1");
            ControlPlaneEmitter emitter = ControlPlaneEmitter.worker(identity, publisher, settings, runtime);
            ConfirmationScope scope = new ConfirmationScope("swarm-A", "generator", "gen-1");

            ControlPlaneEmitter.ReadyContext ready = ControlPlaneEmitter.ReadyContext.builder(
                    "swarm-start",
                    "corr-1",
                    "idem-1",
                    new CommandState(null, null, null))
                .timestamp(Instant.parse("2024-01-01T00:00:00Z"))
                .build();
            emitter.emitReady(ready);

	            ControlSignal signal = ControlSignal.forInstance(
	                "config-update",
	                "swarm-A",
	                "generator",
	                "gen-1",
	                properties.getInstanceId(),
	                "corr-2",
	                "idem-2",
	                null);
            String signalKey = ControlPlaneRouting.signal("config-update", "swarm-A", "generator", "gen-1");
            publisher.publishSignal(new SignalMessage(signalKey, signal));

            ArgumentCaptor<String> routingCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
            verify(template, times(2)).convertAndSend(eq(exchange.getName()), routingCaptor.capture(), payloadCaptor.capture());

            List<String> routes = routingCaptor.getAllValues();
            assertThat(routes).contains(signalKey,
                ControlPlaneRouting.event("outcome", "swarm-start", scope));
            assertThat(payloadCaptor.getAllValues()).allSatisfy(payload -> assertThat(payload).isNotNull());
        });
    }

    @Test
    void disablingControlPlaneSkipsCommonInfrastructure() {
        contextRunner
            .withPropertyValues("pockethive.control-plane.enabled=false")
            .withBean(RabbitTemplate.class, () -> mock(RabbitTemplate.class))
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean("controlPlaneExchange");
                assertThat(context).doesNotHaveBean(ControlPlaneTopologyDeclarableFactory.class);
                assertThat(context).doesNotHaveBean(ControlPlanePublisher.class);
            });
    }
}
