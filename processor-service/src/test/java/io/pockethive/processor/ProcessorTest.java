package io.pockethive.processor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pockethive.Topology;
import io.pockethive.asyncapi.AsyncApiSchemaValidator;
import io.pockethive.control.CommandTarget;
import io.pockethive.control.ConfirmationScope;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.messaging.EventMessage;
import io.pockethive.controlplane.messaging.SignalMessage;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.topology.ControlPlaneRouteCatalog;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.ControlQueueDescriptor;
import io.pockethive.controlplane.topology.ProcessorControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.worker.WorkerControlPlane;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcessorTest {

    @Mock
    RabbitTemplate rabbit;

    @Mock
    RabbitListenerEndpointRegistry listenerRegistry;

    @Mock
    MessageListenerContainer workContainer;

    private static final AsyncApiSchemaValidator ASYNC_API = AsyncApiSchemaValidator.loadDefault();
    private final ObjectMapper mapper = new ObjectMapper();
    private SimpleMeterRegistry meterRegistry;
    private Processor processor;
    private ControlPlaneIdentity identity;
    private ConfirmationScope scope;
    private ControlPlaneTopologyDescriptor topology;
    private CapturingPublisher publisher;
    private String controlQueueName;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        identity = new ControlPlaneIdentity(Topology.SWARM_ID, "processor", "inst");
        scope = new ConfirmationScope(identity.swarmId(), identity.role(), identity.instanceId());
        topology = new ProcessorControlPlaneTopologyDescriptor();
        controlQueueName = topology.controlQueue(identity.instanceId())
            .map(ControlQueueDescriptor::name)
            .orElseThrow();
        publisher = new CapturingPublisher();
        ControlPlaneEmitter emitter = ControlPlaneEmitter.processor(identity, publisher);
        WorkerControlPlane workerControlPlane = WorkerControlPlane.builder(mapper)
            .identity(identity)
            .build();
        processor = new Processor(rabbit, meterRegistry, identity, emitter, topology, workerControlPlane,
            "http://initial", listenerRegistry);
        publisher.clear();
        clearInvocations(rabbit, listenerRegistry, workContainer);
    }

    @Test
    void statusRequestEmitsFullStatus() throws Exception {
        String correlationId = UUID.randomUUID().toString();
        String idempotencyKey = UUID.randomUUID().toString();
        ControlSignal signal = ControlSignal.forInstance(
            "status-request", identity.swarmId(), identity.role(), identity.instanceId(), correlationId, idempotencyKey);

        processor.onControl(mapper.writeValueAsString(signal),
            ControlPlaneRouting.signal("status-request", identity.swarmId(), identity.role(), identity.instanceId()), null);

        assertThat(publisher.events()).hasSize(1);
        EventMessage event = publisher.lastEvent();
        assertThat(event).isNotNull();
        String expectedRoute = ControlPlaneRouting.event("status-full", scope);
        assertThat(event.routingKey()).isEqualTo(expectedRoute);
        JsonNode node = mapper.readTree((String) event.payload());
        List<String> errors = ASYNC_API.validate("#/components/schemas/ControlStatusFullPayload", node);
        assertThat(errors).isEmpty();
        assertThat(node.path("queues").path("control").path("in").get(0).asText()).isEqualTo(controlQueueName);
        List<String> actualRoutes = mapper.convertValue(node.path("queues").path("control").path("routes"),
            new TypeReference<List<String>>() { });
        if (actualRoutes == null) {
            actualRoutes = List.of();
        }
        assertThat(actualRoutes).containsExactlyInAnyOrderElementsOf(resolveRoutes(topology));
        assertThat(node.path("queues").path("work").path("routes").isArray()).isTrue();
        assertThat(node.path("traffic").asText()).isEqualTo(Topology.EXCHANGE);
        assertThat(node.path("data").path("baseUrl").asText()).isEqualTo("http://initial");
        verifyNoInteractions(rabbit);
    }

    @Test
    void configUpdateAppliesArgsAndEmitsConfirmation() throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", true);
        data.put("baseUrl", "http://next");
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("data", data);
        String correlationId = UUID.randomUUID().toString();
        String idempotencyKey = UUID.randomUUID().toString();
        ControlSignal signal = ControlSignal.forInstance(
            "config-update", identity.swarmId(), identity.role(), identity.instanceId(), correlationId, idempotencyKey,
            CommandTarget.INSTANCE, args);

        when(listenerRegistry.getListenerContainer("workListener")).thenReturn(workContainer);
        processor.onControl(mapper.writeValueAsString(signal),
            ControlPlaneRouting.signal("config-update", identity.swarmId(), identity.role(), identity.instanceId()), null);

        Boolean enabled = (Boolean) ReflectionTestUtils.getField(processor, "enabled");
        assertThat(enabled).isTrue();
        String baseUrl = (String) ReflectionTestUtils.getField(processor, "baseUrl");
        assertThat(baseUrl).isEqualTo("http://next");
        verify(workContainer).start();

        assertThat(publisher.events()).hasSize(1);
        EventMessage event = publisher.lastEvent();
        assertThat(event.routingKey()).isEqualTo(ControlPlaneRouting.event("ready", "config-update", scope));
        JsonNode node = mapper.readTree((String) event.payload());
        assertThat(node.path("result").asText()).isEqualTo("success");
        assertThat(node.path("signal").asText()).isEqualTo("config-update");
        assertThat(node.path("correlationId").asText()).isEqualTo(correlationId);
        assertThat(node.path("idempotencyKey").asText()).isEqualTo(idempotencyKey);
        assertThat(node.path("scope").path("role").asText()).isEqualTo(identity.role());
        assertThat(node.path("scope").path("instance").asText()).isEqualTo(identity.instanceId());
        assertThat(node.path("scope").path("swarmId").asText()).isEqualTo(identity.swarmId());
        assertThat(node.path("state").path("enabled").asBoolean()).isTrue();
        List<String> readyErrors = ASYNC_API.validate("#/components/schemas/CommandReadyPayload", node);
        assertThat(readyErrors).isEmpty();
        verifyNoInteractions(rabbit);
    }

    @Test
    void configUpdateErrorEmitsErrorConfirmation() throws Exception {
        Map<String, Object> args = Map.of(
            "data", Map.of("enabled", "oops")
        );
        String correlationId = UUID.randomUUID().toString();
        String idempotencyKey = UUID.randomUUID().toString();
        ControlSignal signal = ControlSignal.forInstance(
            "config-update", identity.swarmId(), identity.role(), identity.instanceId(), correlationId, idempotencyKey,
            CommandTarget.INSTANCE, args);

        processor.onControl(mapper.writeValueAsString(signal),
            ControlPlaneRouting.signal("config-update", identity.swarmId(), identity.role(), identity.instanceId()), null);

        assertThat(publisher.events()).hasSize(1);
        EventMessage event = publisher.lastEvent();
        assertThat(event.routingKey()).isEqualTo(ControlPlaneRouting.event("error", "config-update", scope));
        JsonNode node = mapper.readTree((String) event.payload());
        assertThat(node.path("result").asText()).isEqualTo("error");
        assertThat(node.path("signal").asText()).isEqualTo("config-update");
        assertThat(node.path("correlationId").asText()).isEqualTo(correlationId);
        assertThat(node.path("idempotencyKey").asText()).isEqualTo(idempotencyKey);
        assertThat(node.path("scope").path("role").asText()).isEqualTo(identity.role());
        assertThat(node.path("code").asText()).isEqualTo("IllegalArgumentException");
        assertThat(node.path("message").asText()).isNotBlank();
        assertThat(node.path("state").path("enabled").asBoolean()).isFalse();
        assertThat(node.path("phase").asText()).isEqualTo("apply");
        List<String> errorPayload = ASYNC_API.validate("#/components/schemas/CommandErrorPayload", node);
        assertThat(errorPayload).isEmpty();

        Boolean enabled = (Boolean) ReflectionTestUtils.getField(processor, "enabled");
        assertThat(enabled).isFalse();
        verify(workContainer, never()).start();
        verifyNoInteractions(rabbit);
    }

    @Test
    void onControlRejectsBlankPayload() {
        String routingKey = ControlPlaneRouting.signal("status-request", identity.swarmId(), identity.role(), identity.instanceId());

        assertThatThrownBy(() -> processor.onControl("", routingKey, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("payload");

        assertThat(publisher.events()).isEmpty();
        verifyNoInteractions(rabbit);
        verifyNoInteractions(listenerRegistry);
    }

    @Test
    void onControlRejectsBlankRoutingKey() throws Exception {
        String payload = mapper.writeValueAsString(ControlSignal.forInstance(
            "status-request", identity.swarmId(), identity.role(), identity.instanceId(),
            UUID.randomUUID().toString(), UUID.randomUUID().toString()));

        assertThatThrownBy(() -> processor.onControl(payload, "  ", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("routing key");

        assertThat(publisher.events()).isEmpty();
        verifyNoInteractions(rabbit);
        verifyNoInteractions(listenerRegistry);
    }

    private List<String> resolveRoutes(ControlPlaneTopologyDescriptor descriptor) {
        ControlPlaneRouteCatalog catalog = descriptor.routes();
        List<String> resolved = new ArrayList<>();
        resolved.addAll(expandRoutes(catalog.configSignals()));
        resolved.addAll(expandRoutes(catalog.statusSignals()));
        resolved.addAll(expandRoutes(catalog.lifecycleSignals()));
        resolved.addAll(expandRoutes(catalog.statusEvents()));
        resolved.addAll(expandRoutes(catalog.lifecycleEvents()));
        resolved.addAll(expandRoutes(catalog.otherEvents()));
        return resolved.stream()
            .filter(route -> route != null && !route.isBlank())
            .collect(Collectors.toCollection(LinkedHashSet::new))
            .stream()
            .toList();
    }

    private List<String> expandRoutes(Set<String> templates) {
        if (templates == null || templates.isEmpty()) {
            return List.of();
        }
        return templates.stream()
            .filter(Objects::nonNull)
            .map(route -> route.replace(ControlPlaneRouteCatalog.INSTANCE_TOKEN, identity.instanceId()))
            .toList();
    }

    private static final class CapturingPublisher implements ControlPlanePublisher {

        private final List<EventMessage> events = new ArrayList<>();

        @Override
        public void publishSignal(SignalMessage message) {
            // not required for tests
        }

        @Override
        public void publishEvent(EventMessage message) {
            events.add(message);
        }

        void clear() {
            events.clear();
        }

        EventMessage lastEvent() {
            if (events.isEmpty()) {
                return null;
            }
            return events.get(events.size() - 1);
        }

        List<EventMessage> events() {
            return List.copyOf(events);
        }
    }
}
