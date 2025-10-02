package io.pockethive.moderator;

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
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.ControlQueueDescriptor;
import io.pockethive.controlplane.topology.ModeratorControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.worker.WorkerControlPlane;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModeratorTest {

  private static final AsyncApiSchemaValidator ASYNC_API = AsyncApiSchemaValidator.loadDefault();

  @Mock
  RabbitTemplate rabbit;

  @Mock
  RabbitListenerEndpointRegistry registry;

  @Mock
  MessageListenerContainer container;

  @Mock
  ControlPlaneEmitter controlEmitter;

  private final ObjectMapper mapper = new ObjectMapper();
  private ControlPlaneIdentity identity;
  private ControlPlaneTopologyDescriptor topology;
  private WorkerControlPlane workerControlPlane;
  private Moderator moderator;
  private String controlQueueName;

  @BeforeEach
  void setUp() {
    identity = new ControlPlaneIdentity(Topology.SWARM_ID, "moderator", "inst");
    topology = new ModeratorControlPlaneTopologyDescriptor();
    workerControlPlane = WorkerControlPlane.builder(mapper)
        .identity(identity)
        .build();
    moderator = new Moderator(rabbit, registry, controlEmitter, identity, topology, workerControlPlane);
    clearInvocations(controlEmitter, rabbit, registry, container);
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

    moderator.onControl(mapper.writeValueAsString(signal),
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
    verify(controlEmitter, never()).emitStatusDelta(any());
  }

  @Test
  void configUpdateTogglesListenerAndEmitsReady() throws Exception {
    Map<String, Object> args = Map.of("data", Map.of("enabled", true));
    String correlationId = UUID.randomUUID().toString();
    String idempotencyKey = UUID.randomUUID().toString();
    when(registry.getListenerContainer("workListener")).thenReturn(container);
    ControlSignal signal = ControlSignal.forInstance("config-update", identity.swarmId(),
        identity.role(), identity.instanceId(), correlationId, idempotencyKey,
        CommandTarget.INSTANCE, args);

    moderator.onControl(mapper.writeValueAsString(signal),
        ControlPlaneRouting.signal("config-update", identity.swarmId(), identity.role(),
            identity.instanceId()), null);

    ArgumentCaptor<ControlPlaneEmitter.ReadyContext> captor =
        ArgumentCaptor.forClass(ControlPlaneEmitter.ReadyContext.class);
    verify(controlEmitter).emitReady(captor.capture());
    ControlPlaneEmitter.ReadyContext context = captor.getValue();
    assertThat(context.state().enabled()).isTrue();
    Boolean enabled = (Boolean) ReflectionTestUtils.getField(moderator, "enabled");
    assertThat(enabled).isTrue();
    verify(container).start();
  }

  @Test
  void configUpdateDisablesListenerWhenAlreadyEnabled() throws Exception {
    ReflectionTestUtils.setField(moderator, "enabled", true);
    Map<String, Object> args = Map.of("data", Map.of("enabled", false));
    String correlationId = UUID.randomUUID().toString();
    String idempotencyKey = UUID.randomUUID().toString();
    when(registry.getListenerContainer("workListener")).thenReturn(container);
    ControlSignal signal = ControlSignal.forInstance("config-update", identity.swarmId(),
        identity.role(), identity.instanceId(), correlationId, idempotencyKey,
        CommandTarget.INSTANCE, args);

    moderator.onControl(mapper.writeValueAsString(signal),
        ControlPlaneRouting.signal("config-update", identity.swarmId(), identity.role(),
            identity.instanceId()), null);

    verify(container).stop();
  }

  @Test
  void configUpdateErrorEmitsError() throws Exception {
    Map<String, Object> args = Map.of("data", Map.of("enabled", "oops"));
    String correlationId = UUID.randomUUID().toString();
    String idempotencyKey = UUID.randomUUID().toString();
    ControlSignal signal = ControlSignal.forInstance("config-update", identity.swarmId(),
        identity.role(), identity.instanceId(), correlationId, idempotencyKey,
        CommandTarget.INSTANCE, args);

    moderator.onControl(mapper.writeValueAsString(signal),
        ControlPlaneRouting.signal("config-update", identity.swarmId(), identity.role(),
            identity.instanceId()), null);

    ArgumentCaptor<ControlPlaneEmitter.ErrorContext> captor =
        ArgumentCaptor.forClass(ControlPlaneEmitter.ErrorContext.class);
    verify(controlEmitter).emitError(captor.capture());
    ControlPlaneEmitter.ErrorContext context = captor.getValue();
    assertThat(context.code()).isEqualTo("IllegalArgumentException");
    verify(controlEmitter, never()).emitReady(any());
  }

  @Test
  void onControlRejectsBlankPayload() {
    String routingKey = ControlPlaneRouting.signal("status-request", identity.swarmId(),
        identity.role(), identity.instanceId());

    assertThatThrownBy(() -> moderator.onControl(" ", routingKey, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("payload");
  }

}
