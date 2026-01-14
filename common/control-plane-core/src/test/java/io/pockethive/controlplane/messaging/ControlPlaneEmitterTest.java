package io.pockethive.controlplane.messaging;

import static io.pockethive.controlplane.payload.JsonFixtureAssertions.ANY_VALUE;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pockethive.control.AlertMessage;
import io.pockethive.control.CommandState;
import io.pockethive.control.ConfirmationScope;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.payload.JsonFixtureAssertions;
import io.pockethive.controlplane.payload.RoleContext;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.topology.ControlPlaneTopologySettings;
import java.time.Instant;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ControlPlaneEmitterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private CapturingPublisher publisher;
    private ControlPlaneIdentity identity;
    private ControlPlaneEmitter emitter;
    private ControlPlaneTopologySettings settings;

    @BeforeEach
    void setUp() {
        publisher = new CapturingPublisher();
        identity = new ControlPlaneIdentity("swarm-A", null, "generator", "gen-1");
        settings = new ControlPlaneTopologySettings("swarm-A", "ph.control", Map.of());
        emitter = ControlPlaneEmitter.worker(identity, publisher, settings);
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
        String expectedRoute = ControlPlaneRouting.event("outcome", "swarm-start",
            RoleContext.fromIdentity(identity).toScope());
        assertThat(message.routingKey()).isEqualTo(expectedRoute);

        String json = describeEvent(message, payload -> { });
        JsonFixtureAssertions.assertMatchesFixture(
            "/io/pockethive/controlplane/messaging/ready-event.json",
            json);
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
            .timestamp(Instant.parse("2024-01-02T00:00:00Z"))
            .build();

        emitter.emitError(context);

        assertThat(publisher.events).hasSize(2);
        EventMessage outcomeMessage = publisher.events.getFirst();
        EventMessage alertMessage = publisher.events.get(1);
        String expectedRoute = ControlPlaneRouting.event("outcome", "swarm-stop",
            new ConfirmationScope("swarm-A", "generator", "gen-1"));
        assertThat(outcomeMessage.routingKey()).isEqualTo(expectedRoute);
        assertThat(alertMessage.routingKey()).isEqualTo(
            ControlPlaneRouting.event("alert", "alert",
                new ConfirmationScope("swarm-A", "generator", "gen-1")));

        String json = describeEvent(outcomeMessage, payload -> { });
        JsonFixtureAssertions.assertMatchesFixture(
            "/io/pockethive/controlplane/messaging/error-event.json",
            json);

        AlertMessage alert = MAPPER.readValue((String) alertMessage.payload(), AlertMessage.class);
        assertThat(alert.kind()).isEqualTo("event");
        assertThat(alert.type()).isEqualTo("alert");
        assertThat(alert.correlationId()).isEqualTo("corr-2");
        assertThat(alert.idempotencyKey()).isEqualTo("idem-2");
        assertThat(alert.data().code()).isEqualTo("ERR-42");
        assertThat(alert.data().message()).isEqualTo("Failure");
        assertThat(alert.data().context()).containsEntry("phase", "shutdown");
        assertThat(alert.data().context()).containsEntry("stack", "trace");
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
        String expectedRoute = ControlPlaneRouting.event("metric", "status-delta",
            new ConfirmationScope("swarm-A", "generator", "gen-1"));
        assertThat(message.routingKey()).isEqualTo(expectedRoute);

        ObjectNode payloadNode = (ObjectNode) MAPPER.readTree((String) message.payload());
        assertThat(payloadNode.get("origin").asText()).isEqualTo("gen-1");

        String json = describeEvent(message, payload -> { });
        JsonFixtureAssertions.assertMatchesFixture(
            "/io/pockethive/controlplane/messaging/status-delta-event.json",
            json);
    }

    @Test
    void workerFactoryAcceptsArbitraryRoles() {
        ControlPlaneIdentity custom = new ControlPlaneIdentity("swarm-A", null, "custom-role", "worker-1");
        ControlPlaneEmitter customEmitter = ControlPlaneEmitter.worker(custom, publisher, settings);

        ControlPlaneEmitter.StatusContext context = ControlPlaneEmitter.StatusContext.of(builder -> builder
            .enabled(true)
            .tps(0)
            .data("startedAt", Instant.parse("2024-01-01T00:00:00Z").toString()));
        customEmitter.emitStatusSnapshot(context);

        EventMessage message = publisher.lastEvent;
        assertThat(message.routingKey()).contains("custom-role");
    }

    private static String describeEvent(EventMessage message, Consumer<ObjectNode> payloadCustomiser) throws IOException {
        Object payloadValue = message.payload();
        ObjectNode payload;
        if (payloadValue instanceof String s) {
            payload = (ObjectNode) MAPPER.readTree(s);
        } else {
            JsonNode node = MAPPER.valueToTree(payloadValue);
            payload = (ObjectNode) node;
        }
        if (payloadCustomiser != null) {
            payloadCustomiser.accept(payload);
        }
        ObjectNode document = MAPPER.createObjectNode();
        document.put("routingKey", message.routingKey());
        document.set("payload", payload);
        return MAPPER.writeValueAsString(document);
    }

    private static final class CapturingPublisher implements ControlPlanePublisher {

        private final List<EventMessage> events = new ArrayList<>();
        private EventMessage lastEvent;

        @Override
        public void publishSignal(SignalMessage message) {
            // not used in tests
        }

        @Override
        public void publishEvent(EventMessage message) {
            this.lastEvent = message;
            this.events.add(message);
        }
    }
}
