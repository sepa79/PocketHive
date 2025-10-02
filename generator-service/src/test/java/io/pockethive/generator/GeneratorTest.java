package io.pockethive.generator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.Topology;
import io.pockethive.asyncapi.AsyncApiSchemaValidator;
import io.pockethive.control.CommandTarget;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter;
import io.pockethive.controlplane.payload.RoleContext;
import io.pockethive.controlplane.payload.StatusPayloadFactory;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.topology.ControlPlaneRouteCatalog;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.ControlQueueDescriptor;
import io.pockethive.controlplane.topology.GeneratorControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.worker.WorkerControlPlane;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class GeneratorTest {

  private static final AsyncApiSchemaValidator ASYNC_API = AsyncApiSchemaValidator.loadDefault();

  @Mock
  RabbitTemplate rabbit;

  @Mock
  ControlPlaneEmitter controlEmitter;

  private final ObjectMapper mapper = new ObjectMapper();
  private ControlPlaneIdentity identity;
  private ControlPlaneTopologyDescriptor topology;
  private WorkerControlPlane workerControlPlane;
  private MessageConfig messageConfig;
  private Generator generator;
  private String controlQueueName;

  @BeforeEach
  void setUp() {
    identity = new ControlPlaneIdentity(Topology.SWARM_ID, "generator", "inst");
    topology = new GeneratorControlPlaneTopologyDescriptor();
    workerControlPlane = WorkerControlPlane.builder(mapper)
        .identity(identity)
        .build();
    messageConfig = new MessageConfig();
    messageConfig.setPath("/api/test");
    messageConfig.setMethod("POST");
    messageConfig.setBody("payload");
    messageConfig.setHeaders(Map.of("X-Test", "true"));
    generator = new Generator(rabbit, controlEmitter, identity, topology, workerControlPlane,
        messageConfig, mapper);
    clearInvocations(controlEmitter, rabbit);
    controlQueueName = topology.controlQueue(identity.instanceId())
        .map(ControlQueueDescriptor::name)
        .orElseThrow();
  }

  @Test
  void statusRequestEmitsFullStatus() throws Exception {
    String correlationId = UUID.randomUUID().toString();
    String idempotencyKey = UUID.randomUUID().toString();
    ControlSignal signal = ControlSignal.forInstance("status-request", identity.swarmId(),
        identity.role(), identity.instanceId(), correlationId, idempotencyKey);

    generator.onControl(mapper.writeValueAsString(signal),
        ControlPlaneRouting.signal("status-request", identity.swarmId(), identity.role(),
            identity.instanceId()), null);

    ArgumentCaptor<ControlPlaneEmitter.StatusContext> captor =
        ArgumentCaptor.forClass(ControlPlaneEmitter.StatusContext.class);
    verify(controlEmitter).emitStatusSnapshot(captor.capture());
    ControlPlaneEmitter.StatusContext context = captor.getValue();
      StatusPayloadFactory factory = new StatusPayloadFactory(RoleContext.fromIdentity(identity));
      String payload = factory.snapshot(context.customiser());
    JsonNode node = mapper.readTree(payload);
    List<String> errors = ASYNC_API.validate("#/components/schemas/ControlStatusFullPayload", node);
    assertThat(errors).isEmpty();
    assertThat(node.path("queues").path("control").path("in").get(0).asText())
        .isEqualTo(controlQueueName);
    List<String> actualRoutes = mapper.convertValue(
        node.path("queues").path("control").path("routes"),
        new TypeReference<List<String>>() { });
    if (actualRoutes == null) {
      actualRoutes = List.of();
    }
    assertThat(actualRoutes)
        .containsExactlyInAnyOrderElementsOf(resolveRoutes(topology));
    assertThat(node.path("traffic").asText()).isEqualTo(Topology.EXCHANGE);
    assertThat(node.path("queues").path("work").path("out").get(0).asText())
        .isEqualTo(Topology.GEN_QUEUE);
    verify(controlEmitter, never()).emitStatusDelta(any());
  }

  @Test
  void configUpdateAppliesDataAndEmitsConfirmation() throws Exception {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("enabled", true);
    data.put("ratePerSec", 5.0);
    data.put("singleRequest", true);
    data.put("path", "/other");
    data.put("method", "PUT");
    data.put("body", "{}");
    data.put("headers", Map.of("Accept", "application/json"));
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("data", data);
    String correlationId = UUID.randomUUID().toString();
    String idempotencyKey = UUID.randomUUID().toString();
    ControlSignal signal = ControlSignal.forInstance("config-update", identity.swarmId(),
        identity.role(), identity.instanceId(), correlationId, idempotencyKey,
        CommandTarget.INSTANCE, args);

    generator.onControl(mapper.writeValueAsString(signal),
        ControlPlaneRouting.signal("config-update", identity.swarmId(), identity.role(),
            identity.instanceId()), null);

    ArgumentCaptor<ControlPlaneEmitter.ReadyContext> captor =
        ArgumentCaptor.forClass(ControlPlaneEmitter.ReadyContext.class);
    verify(controlEmitter).emitReady(captor.capture());
    ControlPlaneEmitter.ReadyContext context = captor.getValue();
    assertThat(context.signal()).isEqualTo("config-update");
    assertThat(context.correlationId()).isEqualTo(correlationId);
    assertThat(context.idempotencyKey()).isEqualTo(idempotencyKey);
    assertThat(context.state().enabled()).isTrue();
    Map<String, Object> stateDetails = context.state().details();
    assertThat(stateDetails).isNotNull();
    assertThat(stateDetails).containsEntry("path", "/other");
    assertThat(stateDetails).containsEntry("method", "PUT");
    assertThat(context.result()).isEqualTo("success");
    ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(rabbit).convertAndSend(eq(Topology.EXCHANGE), eq(Topology.GEN_QUEUE), messageCaptor.capture());
    Message outbound = messageCaptor.getValue();
    String bodyJson = new String(outbound.getBody(), StandardCharsets.UTF_8);
    Map<String, Object> emitted = mapper.readValue(bodyJson, new TypeReference<Map<String, Object>>() { });
    assertThat(emitted).containsEntry("path", "/other");
    assertThat(emitted).containsEntry("method", "PUT");
    assertThat(emitted).containsKey("headers");
    assertThat(emitted).containsKey("id");
    assertThat(emitted.get("id")).isEqualTo(outbound.getMessageProperties().getMessageId());
    assertThat(emitted).containsKey("createdAt");
    assertThat(emitted).doesNotContainKeys("messageId", "timestamp", "role", "instance", "swarmId");
    Double rate = (Double) ReflectionTestUtils.getField(generator, "ratePerSec");
    assertThat(rate).isEqualTo(5.0);
    Boolean enabled = (Boolean) ReflectionTestUtils.getField(generator, "enabled");
    assertThat(enabled).isTrue();
  }

  @Test
  void configUpdateErrorEmitsErrorConfirmation() throws Exception {
    Map<String, Object> args = Map.of("data", Map.of("ratePerSec", "oops"));
    String correlationId = UUID.randomUUID().toString();
    String idempotencyKey = UUID.randomUUID().toString();
    ControlSignal signal = ControlSignal.forInstance("config-update", identity.swarmId(),
        identity.role(), identity.instanceId(), correlationId, idempotencyKey,
        CommandTarget.INSTANCE, args);

    generator.onControl(mapper.writeValueAsString(signal),
        ControlPlaneRouting.signal("config-update", identity.swarmId(), identity.role(),
            identity.instanceId()), null);

    ArgumentCaptor<ControlPlaneEmitter.ErrorContext> captor =
        ArgumentCaptor.forClass(ControlPlaneEmitter.ErrorContext.class);
    verify(controlEmitter).emitError(captor.capture());
    ControlPlaneEmitter.ErrorContext context = captor.getValue();
    assertThat(context.signal()).isEqualTo("config-update");
    assertThat(context.correlationId()).isEqualTo(correlationId);
    assertThat(context.state().enabled()).isFalse();
    assertThat(context.code()).isEqualTo("IllegalArgumentException");
    assertThat(context.result()).isEqualTo("error");
    verify(controlEmitter, never()).emitReady(any());
    verify(rabbit, never()).convertAndSend(eq(Topology.CONTROL_EXCHANGE), anyString(), any(Object.class));
  }

  @Test
  void scheduledStatusEmitsDeltaWithConfigurationData() throws Exception {
    clearInvocations(controlEmitter);
    ReflectionTestUtils.setField(generator, "enabled", true);
    ReflectionTestUtils.setField(generator, "lastStatusTs", System.currentTimeMillis() - 100);
    AtomicLong counter = (AtomicLong) ReflectionTestUtils.getField(generator, "counter");
    counter.addAndGet(4);

    generator.status();

    ArgumentCaptor<ControlPlaneEmitter.StatusContext> captor =
        ArgumentCaptor.forClass(ControlPlaneEmitter.StatusContext.class);
    verify(controlEmitter).emitStatusDelta(captor.capture());
    ControlPlaneEmitter.StatusContext context = captor.getValue();
    StatusPayloadFactory factory = new StatusPayloadFactory(RoleContext.fromIdentity(identity));
    String payload = factory.delta(context.customiser());
    JsonNode node = mapper.readTree(payload);
    assertThat(node.path("queues").path("work").path("out").get(0).asText())
        .isEqualTo(Topology.GEN_QUEUE);
    assertThat(node.path("data").path("path").asText()).isEqualTo(messageConfig.getPath());
    assertThat(node.path("data").path("method").asText()).isEqualTo(messageConfig.getMethod());
    assertThat(node.path("data").path("headers").path("X-Test").asText()).isEqualTo("true");
    assertThat(node.path("enabled").asBoolean()).isTrue();
    assertThat(node.path("data").path("tps").asLong()).isGreaterThan(0);
  }

  @Test
  void onControlRejectsBlankPayload() {
    String routingKey = ControlPlaneRouting.signal("status-request", identity.swarmId(),
        identity.role(), identity.instanceId());

    assertThatThrownBy(() -> generator.onControl(" ", routingKey, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("payload");

    verify(controlEmitter, never()).emitStatusSnapshot(any());
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
}
