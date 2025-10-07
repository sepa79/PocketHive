package io.pockethive.moderator;

import io.pockethive.Topology;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.config.WorkerType;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRegistry;
import io.pockethive.worker.sdk.runtime.WorkerRuntime;
import io.pockethive.worker.sdk.transport.rabbit.RabbitWorkMessageConverter;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModeratorRuntimeAdapterTest {

  @Mock
  private WorkerRuntime workerRuntime;

  @Mock
  private WorkerRegistry workerRegistry;

  @Mock
  private WorkerControlPlaneRuntime controlPlaneRuntime;

  @Mock
  private RabbitTemplate rabbitTemplate;

  @Mock
  private RabbitListenerEndpointRegistry listenerRegistry;

  @Mock
  private MessageListenerContainer listenerContainer;

  private ModeratorDefaults defaults;
  private WorkerDefinition definition;
  private ControlPlaneIdentity identity;

  @BeforeEach
  void setUp() {
    defaults = new ModeratorDefaults();
    defaults.setEnabled(true);
    identity = new ControlPlaneIdentity(Topology.SWARM_ID, "moderator", "instance-1");
    definition = new WorkerDefinition(
        "moderatorWorker",
        ModeratorWorkerImpl.class,
        WorkerType.MESSAGE,
        "moderator",
        Topology.GEN_QUEUE,
        Topology.MOD_QUEUE,
        ModeratorWorkerConfig.class
    );
    when(workerRegistry.findByRoleAndType("moderator", WorkerType.MESSAGE))
        .thenReturn(Optional.of(definition));
  }

  private void stubListenerContainerStopped() {
    when(listenerRegistry.getListenerContainer("moderatorWorkerListener")).thenReturn(listenerContainer);
    when(listenerContainer.isRunning()).thenReturn(false);
  }

  @Test
  void onWorkDispatchesToWorkerAndPublishesResult() throws Exception {
    stubListenerContainerStopped();
    doReturn(WorkResult.message(WorkMessage.text("forwarded").build()))
        .when(workerRuntime)
        .dispatch(eq("moderatorWorker"), any(WorkMessage.class));

    ModeratorRuntimeAdapter adapter = new ModeratorRuntimeAdapter(
        workerRuntime,
        workerRegistry,
        controlPlaneRuntime,
        rabbitTemplate,
        listenerRegistry,
        identity,
        defaults
    );

    adapter.initialiseStateListener();
  verify(controlPlaneRuntime).emitStatusSnapshot();

    Message inbound = new RabbitWorkMessageConverter().toMessage(WorkMessage.text("body").build());
    adapter.onWork(inbound);

    verify(workerRuntime).dispatch(eq("moderatorWorker"), any(WorkMessage.class));
    ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(rabbitTemplate).send(eq(Topology.EXCHANGE), eq(Topology.MOD_QUEUE), messageCaptor.capture());
    assertThat(new String(messageCaptor.getValue().getBody(), StandardCharsets.UTF_8))
        .isEqualTo("forwarded");
  }

  @Test
  void onControlDelegatesToControlPlaneRuntime() {
    stubListenerContainerStopped();
    ModeratorRuntimeAdapter adapter = new ModeratorRuntimeAdapter(
        workerRuntime,
        workerRegistry,
        controlPlaneRuntime,
        rabbitTemplate,
        listenerRegistry,
        identity,
        defaults
    );

    adapter.initialiseStateListener();
  verify(controlPlaneRuntime).emitStatusSnapshot();

    adapter.onControl("{}", "moderator.control", null);
    verify(controlPlaneRuntime).handle("{}", "moderator.control");

    assertThatThrownBy(() -> adapter.onControl(" ", "moderator.control", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("payload");

    assertThatThrownBy(() -> adapter.onControl("{}", " ", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("routing key");
  }

  @Test
  void registersListenerAndAppliesDesiredState() {
    stubListenerContainerStopped();
    ModeratorRuntimeAdapter adapter = new ModeratorRuntimeAdapter(
        workerRuntime,
        workerRegistry,
        controlPlaneRuntime,
        rabbitTemplate,
        listenerRegistry,
        identity,
        defaults
    );

    adapter.initialiseStateListener();
    verify(controlPlaneRuntime).emitStatusSnapshot();

    ArgumentCaptor<String> beanCaptor = ArgumentCaptor.forClass(String.class);
    verify(controlPlaneRuntime).registerStateListener(beanCaptor.capture(), any());
    assertThat(beanCaptor.getValue()).isEqualTo("moderatorWorker");
    verify(listenerContainer, times(1)).start();
  }

  @Test
  void emitStatusDeltaDelegatesToControlPlaneRuntime() {
    ModeratorRuntimeAdapter adapter = new ModeratorRuntimeAdapter(
        workerRuntime,
        workerRegistry,
        controlPlaneRuntime,
        rabbitTemplate,
        listenerRegistry,
        identity,
        defaults
    );

    adapter.emitStatusDelta();

    verify(controlPlaneRuntime).emitStatusDelta();
  }
}
