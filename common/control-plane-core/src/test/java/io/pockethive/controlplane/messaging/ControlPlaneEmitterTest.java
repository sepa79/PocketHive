package io.pockethive.controlplane.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.CommandState;
import io.pockethive.control.ConfirmationScope;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.payload.RoleContext;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ControlPlaneEmitterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private CapturingPublisher publisher;
    private ControlPlaneIdentity identity;
    private ControlPlaneEmitter emitter;

    @BeforeEach
    void setUp() {
        publisher = new CapturingPublisher();
        identity = new ControlPlaneIdentity("swarm-A", "generator", "gen-1");
        emitter = ControlPlaneEmitter.generator(identity, publisher);
    }

    @Test
    void emitReadyPublishesLifecycleEvent() throws Exception {
        CommandState state = new CommandState("Running", true, Map.of("tasks", 5));
        ControlPlaneEmitter.ReadyContext context = ControlPlaneEmitter.ReadyContext.builder(
                "swarm-start",
                "corr-1",
                "idem-1",
                state)
            .result("success")
            .details(Map.of("durationMs", 120))
            .timestamp(Instant.parse("2024-01-01T00:00:00Z"))
            .build();

        emitter.emitReady(context);

        EventMessage message = publisher.lastEvent;
        assertThat(message).isNotNull();
        String expectedRoute = ControlPlaneRouting.event("ready", "swarm-start",
            RoleContext.fromIdentity(identity).toScope());
        assertThat(message.routingKey()).isEqualTo(expectedRoute);
        JsonNode payload = MAPPER.readTree((String) message.payload());
        assertThat(payload.get("signal").asText()).isEqualTo("swarm-start");
        assertThat(payload.get("correlationId").asText()).isEqualTo("corr-1");
        assertThat(payload.get("scope").get("role").asText()).isEqualTo("generator");
        assertThat(payload.get("state").get("details").get("tasks").asInt()).isEqualTo(5);
        assertThat(payload.get("details").get("durationMs").asInt()).isEqualTo(120);
    }

    @Test
    void emitErrorPublishesLifecycleEvent() throws Exception {
        CommandState state = CommandState.status("Failed");
        ControlPlaneEmitter.ErrorContext context = ControlPlaneEmitter.ErrorContext.builder(
                "swarm-stop",
                "corr-2",
                "idem-2",
                state,
                "shutdown",
                "ERR-42",
                "Failure")
            .retryable(Boolean.FALSE)
            .result("error")
            .details(Map.of("stack", "trace"))
            .build();

        emitter.emitError(context);

        EventMessage message = publisher.lastEvent;
        assertThat(message).isNotNull();
        String expectedRoute = ControlPlaneRouting.event("error", "swarm-stop",
            new ConfirmationScope("swarm-A", "generator", "gen-1"));
        assertThat(message.routingKey()).isEqualTo(expectedRoute);
        JsonNode payload = MAPPER.readTree((String) message.payload());
        assertThat(payload.get("signal").asText()).isEqualTo("swarm-stop");
        assertThat(payload.get("phase").asText()).isEqualTo("shutdown");
        assertThat(payload.get("code").asText()).isEqualTo("ERR-42");
        assertThat(payload.get("retryable").asBoolean()).isFalse();
        assertThat(payload.get("details").get("stack").asText()).isEqualTo("trace");
    }

    @Test
    void emitStatusDeltaPublishesStatusEvent() throws Exception {
        ControlPlaneEmitter.StatusContext context = ControlPlaneEmitter.StatusContext.of(builder -> builder
            .traffic("exchange.main")
            .workOut("queue.work")
            .enabled(true)
            .tps(7)
            .data("custom", "value"));

        emitter.emitStatusDelta(context);

        EventMessage message = publisher.lastEvent;
        assertThat(message).isNotNull();
        String expectedRoute = ControlPlaneRouting.event("status-delta",
            new ConfirmationScope("swarm-A", "generator", "gen-1"));
        assertThat(message.routingKey()).isEqualTo(expectedRoute);
        JsonNode payload = MAPPER.readTree((String) message.payload());
        assertThat(payload.get("kind").asText()).isEqualTo("status-delta");
        assertThat(payload.get("role").asText()).isEqualTo("generator");
        assertThat(payload.path("queues").path("control").path("out").get(0).asText())
            .isEqualTo(expectedRoute);
        assertThat(payload.path("data").path("custom").asText()).isEqualTo("value");
    }

    @Test
    void generatorFacadeRejectsMismatchedIdentity() {
        ControlPlaneIdentity wrong = new ControlPlaneIdentity("swarm-A", "processor", "gen-1");
        assertThatThrownBy(() -> ControlPlaneEmitter.generator(wrong, publisher))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Identity role mismatch");
    }

    private static final class CapturingPublisher implements ControlPlanePublisher {

        private EventMessage lastEvent;

        @Override
        public void publishSignal(SignalMessage message) {
            // not used in tests
        }

        @Override
        public void publishEvent(EventMessage message) {
            this.lastEvent = message;
        }
    }
}
