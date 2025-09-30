package io.pockethive.postprocessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import io.pockethive.controlplane.topology.PostProcessorControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.worker.WorkerControlPlane;
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
class PostProcessorTest {

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
  private SimpleMeterRegistry meterRegistry;
  private ControlPlaneIdentity identity;
  private ControlPlaneTopologyDescriptor topology;
  private WorkerControlPlane workerControlPlane;
  private PostProcessor postProcessor;
  private String controlQueueName;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    identity = new ControlPlaneIdentity(Topology.SWARM_ID, "postprocessor", "inst");
    topology = new PostProcessorControlPlaneTopologyDescriptor();
    workerControlPlane = WorkerControlPlane.builder(mapper)
        .identity(identity)
        .build();
    postProcessor = new PostProcessor(rabbit, meterRegistry, controlEmitter, identity, topology,
        workerControlPlane, registry);
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

    postProcessor.onControl(mapper.writeValueAsString(signal),
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
  }

  @Test
  void configUpdateEnablesProcessingAndEmitsReady() throws Exception {
    Map<String, Object> args = Map.of("data", Map.of("enabled", true));
    String correlationId = UUID.randomUUID().toString();
    String idempotencyKey = UUID.randomUUID().toString();
    when(registry.getListenerContainer("workListener")).thenReturn(container);
    ControlSignal signal = ControlSignal.forInstance("config-update", identity.swarmId(),
        identity.role(), identity.instanceId(), correlationId, idempotencyKey,
        CommandTarget.INSTANCE, args);

    postProcessor.onControl(mapper.writeValueAsString(signal),
        ControlPlaneRouting.signal("config-update", identity.swarmId(), identity.role(),
            identity.instanceId()), null);

    ArgumentCaptor<ControlPlaneEmitter.ReadyContext> captor =
        ArgumentCaptor.forClass(ControlPlaneEmitter.ReadyContext.class);
    verify(controlEmitter).emitReady(captor.capture());
    Boolean enabled = (Boolean) ReflectionTestUtils.getField(postProcessor, "enabled");
    assertThat(enabled).isTrue();
    verify(container).start();
  }

  @Test
  void configUpdateErrorEmitsError() throws Exception {
    Map<String, Object> args = Map.of("data", Map.of("enabled", "oops"));
    String correlationId = UUID.randomUUID().toString();
    String idempotencyKey = UUID.randomUUID().toString();
    ControlSignal signal = ControlSignal.forInstance("config-update", identity.swarmId(),
        identity.role(), identity.instanceId(), correlationId, idempotencyKey,
        CommandTarget.INSTANCE, args);

    postProcessor.onControl(mapper.writeValueAsString(signal),
        ControlPlaneRouting.signal("config-update", identity.swarmId(), identity.role(),
            identity.instanceId()), null);

    ArgumentCaptor<ControlPlaneEmitter.ErrorContext> captor =
        ArgumentCaptor.forClass(ControlPlaneEmitter.ErrorContext.class);
    verify(controlEmitter).emitError(captor.capture());
    verify(controlEmitter, never()).emitReady(any());
  }

  @Test
  void onControlRejectsBlankPayload() {
    String routingKey = ControlPlaneRouting.signal("status-request", identity.swarmId(),
        identity.role(), identity.instanceId());

    assertThatThrownBy(() -> postProcessor.onControl("", routingKey, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("payload");
  }
}
