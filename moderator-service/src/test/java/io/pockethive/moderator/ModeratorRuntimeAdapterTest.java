package io.pockethive.moderator;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.spring.WorkerControlPlaneProperties;
import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.autoconfigure.WorkerControlQueueListener;
import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRegistry;
import io.pockethive.worker.sdk.runtime.WorkerRuntime;
import io.pockethive.worker.sdk.transport.rabbit.RabbitWorkMessageConverter;
import io.pockethive.worker.sdk.input.WorkInput;
import io.pockethive.worker.sdk.input.WorkInputRegistry;
import io.pockethive.worker.sdk.input.rabbit.RabbitWorkInput;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Consumer;
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

import io.pockethive.worker.sdk.testing.ControlPlaneTestFixtures;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
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

  @Mock
  private WorkInputRegistry workInputRegistry;

  private ModeratorDefaults defaults;
  private WorkerDefinition definition;
  private ControlPlaneIdentity identity;

  private static final WorkerControlPlaneProperties WORKER_PROPERTIES =
      ControlPlaneTestFixtures.workerProperties("swarm-alpha", "moderator", "instance-1");
  private static final String IN_QUEUE = WORKER_PROPERTIES.getQueues().get("generator");
  private static final String OUT_QUEUE = WORKER_PROPERTIES.getQueues().get("moderator");
  private static final String EXCHANGE = WORKER_PROPERTIES.getTrafficExchange();

  @BeforeEach
  void setUp() {
    defaults = new ModeratorDefaults();
    defaults.setEnabled(true);
    identity = new ControlPlaneIdentity(
        WORKER_PROPERTIES.getSwarmId(),
        "moderator",
        WORKER_PROPERTIES.getInstanceId()
    );
    definition = new WorkerDefinition(
        "moderatorWorker",
        ModeratorWorkerImpl.class,
        WorkerInputType.RABBIT,
        "moderator",
        IN_QUEUE,
        OUT_QUEUE,
        EXCHANGE,
        ModeratorWorkerConfig.class
    );
  }

  private void stubListenerContainerStopped() {
    when(listenerRegistry.getListenerContainer("moderatorWorkerListener")).thenReturn(listenerContainer);
    when(listenerContainer.isRunning()).thenReturn(false);
  }

  @Test
  void onWorkDispatchesToWorkerAndPublishesResult() throws Exception {
    when(workerRegistry.findByRoleAndInput("moderator", WorkerInputType.RABBIT))
        .thenReturn(Optional.of(definition));
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
        defaults,
        workInputRegistry
    );
    adapter.start();
    ArgumentCaptor<WorkInput> workInputCaptor = ArgumentCaptor.forClass(WorkInput.class);
    verify(workInputRegistry).register(eq(definition), workInputCaptor.capture());
    assertThat(workInputCaptor.getValue()).isInstanceOf(RabbitWorkInput.class);
    ArgumentCaptor<Object> defaultConfigCaptor = ArgumentCaptor.forClass(Object.class);
    verify(controlPlaneRuntime).registerDefaultConfig(eq("moderatorWorker"), defaultConfigCaptor.capture());
    assertThat(defaultConfigCaptor.getValue()).isEqualTo(
        new ModeratorWorkerConfig(true, ModeratorWorkerConfig.Mode.passThrough()));
    verify(controlPlaneRuntime).emitStatusSnapshot();

    Message inbound = new RabbitWorkMessageConverter().toMessage(WorkMessage.text("body").build());
    adapter.onWork(inbound);

    verify(workerRuntime).dispatch(eq("moderatorWorker"), any(WorkMessage.class));
    ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(rabbitTemplate).send(eq(definition.exchange()), eq(definition.outQueue()), messageCaptor.capture());
    assertThat(new String(messageCaptor.getValue().getBody(), StandardCharsets.UTF_8))
        .isEqualTo("forwarded");
  }

  @Test
  void controlQueueListenerDelegatesToControlPlaneRuntime() {
    WorkerControlQueueListener listener = new WorkerControlQueueListener(controlPlaneRuntime);

    listener.onControl("{}", "moderator.control", null);
    verify(controlPlaneRuntime).handle("{}", "moderator.control");

    assertThatThrownBy(() -> listener.onControl(" ", "moderator.control", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("payload");

    assertThatThrownBy(() -> listener.onControl("{}", " ", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("routing key");
  }

  @Test
  void registersListenerAndAppliesDesiredState() {
    when(workerRegistry.findByRoleAndInput("moderator", WorkerInputType.RABBIT))
        .thenReturn(Optional.of(definition));
    stubListenerContainerStopped();
    ModeratorRuntimeAdapter adapter = new ModeratorRuntimeAdapter(
        workerRuntime,
        workerRegistry,
        controlPlaneRuntime,
        rabbitTemplate,
        listenerRegistry,
        identity,
        defaults,
        workInputRegistry
    );

    adapter.start();
    ArgumentCaptor<WorkInput> registrar = ArgumentCaptor.forClass(WorkInput.class);
    verify(workInputRegistry).register(eq(definition), registrar.capture());
    assertThat(registrar.getValue()).isInstanceOf(RabbitWorkInput.class);
    ArgumentCaptor<Object> defaultConfigCaptor = ArgumentCaptor.forClass(Object.class);
    verify(controlPlaneRuntime).registerDefaultConfig(eq("moderatorWorker"), defaultConfigCaptor.capture());
    assertThat(defaultConfigCaptor.getValue()).isEqualTo(
        new ModeratorWorkerConfig(true, ModeratorWorkerConfig.Mode.passThrough()));
    ArgumentCaptor<Consumer<WorkerControlPlaneRuntime.WorkerStateSnapshot>> listenerCaptor = ArgumentCaptor.forClass(Consumer.class);
    verify(controlPlaneRuntime).registerStateListener(eq("moderatorWorker"), listenerCaptor.capture());
    verify(listenerContainer, times(1)).start();
    verify(controlPlaneRuntime).emitStatusSnapshot();

    WorkerControlPlaneRuntime.WorkerStateSnapshot snapshot = mock(WorkerControlPlaneRuntime.WorkerStateSnapshot.class);
    when(snapshot.enabled()).thenReturn(Optional.empty());
    when(snapshot.config(ModeratorWorkerConfig.class)).thenReturn(Optional.of(
        new ModeratorWorkerConfig(false, ModeratorWorkerConfig.Mode.passThrough())));
    when(listenerContainer.isRunning()).thenReturn(true);

    listenerCaptor.getValue().accept(snapshot);
    verify(listenerContainer).stop();
  }

  @Test
  void onWorkDelegatesErrorsToDispatchHandler() throws Exception {
    when(workerRegistry.findByRoleAndInput("moderator", WorkerInputType.RABBIT))
        .thenReturn(Optional.of(definition));
    ModeratorRuntimeAdapter adapter = new ModeratorRuntimeAdapter(
        workerRuntime,
        workerRegistry,
        controlPlaneRuntime,
        rabbitTemplate,
        listenerRegistry,
        identity,
        defaults,
        workInputRegistry
    );
    adapter.start();

    Message inbound = new RabbitWorkMessageConverter().toMessage(WorkMessage.text("payload").build());
    doThrow(new RuntimeException("boom")).when(workerRuntime)
        .dispatch(eq("moderatorWorker"), any(WorkMessage.class));

    assertThatCode(() -> adapter.onWork(inbound)).doesNotThrowAnyException();
  }

}
