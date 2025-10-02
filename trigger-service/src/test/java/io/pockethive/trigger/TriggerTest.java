package io.pockethive.trigger;

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
import io.pockethive.controlplane.topology.TriggerControlPlaneTopologyDescriptor;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TriggerTest {

  private static final AsyncApiSchemaValidator ASYNC_API = AsyncApiSchemaValidator.loadDefault();

  @Mock
  RabbitTemplate rabbit;

  @Mock
  ControlPlaneEmitter controlEmitter;

  private final ObjectMapper mapper = new ObjectMapper();
  private ControlPlaneIdentity identity;
  private ControlPlaneTopologyDescriptor topology;
  private WorkerControlPlane workerControlPlane;
  private TriggerConfig triggerConfig;
  private Trigger trigger;
  private String controlQueueName;

  @BeforeEach
  void setUp() {
    identity = new ControlPlaneIdentity(Topology.SWARM_ID, "trigger", "inst");
    topology = new TriggerControlPlaneTopologyDescriptor();
    workerControlPlane = WorkerControlPlane.builder(mapper)
        .identity(identity)
        .build();
    triggerConfig = new TriggerConfig();
    triggerConfig.setActionType("none");
    trigger = new Trigger(rabbit, controlEmitter, identity, topology, workerControlPlane,
        triggerConfig, mapper);
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

    trigger.onControl(mapper.writeValueAsString(signal),
        ControlPlaneRouting.signal("status-request", identity.swarmId(), identity.role(),
            identity.instanceId()), null);

    ArgumentCaptor<ControlPlaneEmitter.StatusContext> captor =
        ArgumentCaptor.forClass(ControlPlaneEmitter.StatusContext.class);
    verify(controlEmitter).emitStatusSnapshot(captor.capture());
    StatusPayloadFactory factory = new StatusPayloadFactory(RoleContext.fromIdentity(identity));
    String payload = factory.snapshot(captor.getValue().customiser());
    JsonNode node = mapper.readTree(payload);
    List<String> errors = ASYNC_API.validate("#/components/schemas/ControlStatusFullPayload", node);
    assertThat(errors).isEmpty();
    assertThat(node.path("queues").path("control").path("in").get(0).asText())
        .isEqualTo(controlQueueName);
    List<String> routes = mapper.convertValue(node.path("queues").path("control").path("routes"),
        new TypeReference<List<String>>() { });
    if (routes == null) {
      routes = List.of();
    }
    assertThat(routes).containsExactlyInAnyOrderElementsOf(resolveRoutes(topology));
    assertThat(node.path("traffic").asText()).isEqualTo(Topology.EXCHANGE);
    assertThat(node.path("enabled").asBoolean()).isFalse();
    JsonNode data = node.path("data");
    assertThat(data.has("intervalMs")).isTrue();
    assertThat(data.path("intervalMs").asLong()).isEqualTo(triggerConfig.getIntervalMs());
    assertThat(data.has("actionType")).isTrue();
    assertThat(data.has("headers")).isTrue();
    assertThat(data.has("lastRunTs")).isTrue();
    verify(controlEmitter, never()).emitStatusDelta(any());
  }

  @Test
  void configUpdateAppliesSettingsAndEmitsReady() throws Exception {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("enabled", true);
    data.put("intervalMs", 2000L);
    data.put("actionType", "shell");
    data.put("command", "echo hi");
    Map<String, Object> args = Map.of("data", data);
    String correlationId = UUID.randomUUID().toString();
    String idempotencyKey = UUID.randomUUID().toString();
    ControlSignal signal = ControlSignal.forInstance("config-update", identity.swarmId(),
        identity.role(), identity.instanceId(), correlationId, idempotencyKey,
        CommandTarget.INSTANCE, args);

    trigger.onControl(mapper.writeValueAsString(signal),
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
    Map<String, Object> details = context.state().details();
    assertThat(details).containsEntry("actionType", "shell");
    assertThat(details).containsKey("lastRunTs");
    Long interval = (Long) ReflectionTestUtils.getField(triggerConfig, "intervalMs");
    assertThat(interval).isEqualTo(2000L);
    Boolean enabled = (Boolean) ReflectionTestUtils.getField(trigger, "enabled");
    assertThat(enabled).isTrue();
  }

  @Test
  void configUpdateErrorEmitsError() throws Exception {
    Map<String, Object> args = Map.of("data", Map.of("intervalMs", "oops"));
    String correlationId = UUID.randomUUID().toString();
    String idempotencyKey = UUID.randomUUID().toString();
    ControlSignal signal = ControlSignal.forInstance("config-update", identity.swarmId(),
        identity.role(), identity.instanceId(), correlationId, idempotencyKey,
        CommandTarget.INSTANCE, args);

    trigger.onControl(mapper.writeValueAsString(signal),
        ControlPlaneRouting.signal("config-update", identity.swarmId(), identity.role(),
            identity.instanceId()), null);

    ArgumentCaptor<ControlPlaneEmitter.ErrorContext> captor =
        ArgumentCaptor.forClass(ControlPlaneEmitter.ErrorContext.class);
    verify(controlEmitter).emitError(captor.capture());
    ControlPlaneEmitter.ErrorContext context = captor.getValue();
    assertThat(context.signal()).isEqualTo("config-update");
    assertThat(context.code()).isEqualTo("IllegalArgumentException");
    assertThat(context.result()).isEqualTo("error");
    verify(controlEmitter, never()).emitReady(any());
  }

  @Test
  void onControlRejectsBlankPayload() {
    String routingKey = ControlPlaneRouting.signal("status-request", identity.swarmId(),
        identity.role(), identity.instanceId());

    assertThatThrownBy(() -> trigger.onControl("", routingKey, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("payload");
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
