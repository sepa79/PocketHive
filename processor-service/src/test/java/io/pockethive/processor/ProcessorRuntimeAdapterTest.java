package io.pockethive.processor;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.autoconfigure.WorkerControlQueueListener;
import io.pockethive.worker.sdk.config.WorkerType;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRegistry;
import io.pockethive.worker.sdk.runtime.WorkerRuntime;
import io.pockethive.worker.sdk.transport.rabbit.RabbitWorkMessageConverter;
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
class ProcessorRuntimeAdapterTest {

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

  private ProcessorDefaults defaults;
  private WorkerDefinition definition;
  private ControlPlaneIdentity identity;

  private static final String SWARM_ID = "swarm-alpha";
  private static final String IN_QUEUE = "swarm-alpha.moderation";
  private static final String OUT_QUEUE = "swarm-alpha.final";
  private static final String EXCHANGE = "swarm-alpha.hive";

  @BeforeEach
  void setUp() {
    defaults = new ProcessorDefaults();
    defaults.setEnabled(true);
    defaults.setBaseUrl("http://sut/");
    identity = new ControlPlaneIdentity(SWARM_ID, "processor", "instance-1");
    definition = new WorkerDefinition(
        "processorWorker",
        ProcessorWorkerImpl.class,
        WorkerType.MESSAGE,
        "processor",
        IN_QUEUE,
        OUT_QUEUE,
        EXCHANGE,
        ProcessorWorkerConfig.class
    );
  }

  @Test
  void onWorkDispatchesToWorkerAndPublishesResult() throws Exception {
    when(workerRegistry.findByRoleAndType("processor", WorkerType.MESSAGE))
        .thenReturn(Optional.of(definition));
    doReturn(WorkResult.message(WorkMessage.text("processed").build()))
        .when(workerRuntime)
        .dispatch(eq("processorWorker"), any(WorkMessage.class));

    ProcessorRuntimeAdapter adapter = new ProcessorRuntimeAdapter(
        workerRuntime,
        workerRegistry,
        controlPlaneRuntime,
        rabbitTemplate,
        listenerRegistry,
        identity,
        defaults
    );

    adapter.initialiseStateListener();
    ArgumentCaptor<Object> defaultConfigCaptor = ArgumentCaptor.forClass(Object.class);
    verify(controlPlaneRuntime).registerDefaultConfig(eq("processorWorker"), defaultConfigCaptor.capture());
    assertThat(defaultConfigCaptor.getValue())
        .isEqualTo(new ProcessorWorkerConfig(true, "http://sut/"));
    verify(controlPlaneRuntime).emitStatusSnapshot();

    Message inbound = new RabbitWorkMessageConverter().toMessage(WorkMessage.text("payload").build());
    adapter.onWork(inbound);

    verify(workerRuntime).dispatch(eq("processorWorker"), any(WorkMessage.class));
    ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(rabbitTemplate).send(eq(definition.exchange()), eq(definition.outQueue()), messageCaptor.capture());
    assertThat(new String(messageCaptor.getValue().getBody(), StandardCharsets.UTF_8))
        .isEqualTo("processed");
  }

  @Test
  void controlQueueListenerDelegatesToControlPlaneRuntime() {
    WorkerControlQueueListener listener = new WorkerControlQueueListener(controlPlaneRuntime);

    listener.onControl("{}", "processor.control", null);
    verify(controlPlaneRuntime).handle("{}", "processor.control");

    assertThatThrownBy(() -> listener.onControl(" ", "processor.control", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("payload");

    assertThatThrownBy(() -> listener.onControl("{}", " ", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("routing key");
  }

  @Test
  void registersListenerAppliesDesiredStateAndEmitsSnapshot() {
    when(workerRegistry.findByRoleAndType("processor", WorkerType.MESSAGE))
        .thenReturn(Optional.of(definition));
    when(listenerRegistry.getListenerContainer("processorWorkerListener")).thenReturn(listenerContainer);
    when(listenerContainer.isRunning()).thenReturn(false);

    ProcessorRuntimeAdapter adapter = new ProcessorRuntimeAdapter(
        workerRuntime,
        workerRegistry,
        controlPlaneRuntime,
        rabbitTemplate,
        listenerRegistry,
        identity,
        defaults
    );

    adapter.initialiseStateListener();

    ArgumentCaptor<Object> defaultConfigCaptor = ArgumentCaptor.forClass(Object.class);
    verify(controlPlaneRuntime).registerDefaultConfig(eq("processorWorker"), defaultConfigCaptor.capture());
    assertThat(defaultConfigCaptor.getValue()).isEqualTo(new ProcessorWorkerConfig(true, "http://sut/"));
    ArgumentCaptor<Consumer<WorkerControlPlaneRuntime.WorkerStateSnapshot>> listenerCaptor = ArgumentCaptor.forClass(Consumer.class);
    verify(controlPlaneRuntime).registerStateListener(eq("processorWorker"), listenerCaptor.capture());
    verify(listenerContainer, times(1)).start();
    verify(controlPlaneRuntime).emitStatusSnapshot();

    WorkerControlPlaneRuntime.WorkerStateSnapshot snapshot = mock(WorkerControlPlaneRuntime.WorkerStateSnapshot.class);
    when(snapshot.enabled()).thenReturn(Optional.empty());
    when(snapshot.config(ProcessorWorkerConfig.class))
        .thenReturn(Optional.of(new ProcessorWorkerConfig(false, "http://sut/")));
    when(listenerContainer.isRunning()).thenReturn(true);

    listenerCaptor.getValue().accept(snapshot);
    verify(listenerContainer).stop();
  }

  @Test
  void failsFastWhenOutboundQueueNotDefined() {
    WorkerDefinition missingOutbound = new WorkerDefinition(
        "processorWorker",
        ProcessorWorkerImpl.class,
        WorkerType.MESSAGE,
        "processor",
        IN_QUEUE,
        null,
        EXCHANGE,
        ProcessorWorkerConfig.class
    );
    when(workerRegistry.findByRoleAndType("processor", WorkerType.MESSAGE))
        .thenReturn(Optional.of(missingOutbound));

    assertThatThrownBy(() -> new ProcessorRuntimeAdapter(
        workerRuntime,
        workerRegistry,
        controlPlaneRuntime,
        rabbitTemplate,
        listenerRegistry,
        identity,
        defaults
    ))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("outbound queue");
  }

  @Test
  void onWorkDelegatesErrorsToDispatchHandler() throws Exception {
    when(workerRegistry.findByRoleAndType("processor", WorkerType.MESSAGE))
        .thenReturn(Optional.of(definition));
    ProcessorRuntimeAdapter adapter = new ProcessorRuntimeAdapter(
        workerRuntime,
        workerRegistry,
        controlPlaneRuntime,
        rabbitTemplate,
        listenerRegistry,
        identity,
        defaults
    );

    Message inbound = new RabbitWorkMessageConverter().toMessage(WorkMessage.text("payload").build());
    doThrow(new RuntimeException("boom")).when(workerRuntime)
        .dispatch(eq("processorWorker"), any(WorkMessage.class));

    assertThatCode(() -> adapter.onWork(inbound)).doesNotThrowAnyException();
  }
}
