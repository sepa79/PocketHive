package io.pockethive.processor;

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
import java.util.List;
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

  @BeforeEach
  void setUp() {
    defaults = new ProcessorDefaults();
    defaults.setEnabled(true);
    defaults.setBaseUrl("http://sut/");
    identity = new ControlPlaneIdentity(Topology.SWARM_ID, "processor", "instance-1");
    definition = new WorkerDefinition(
        "processorWorker",
        ProcessorWorkerImpl.class,
        WorkerType.MESSAGE,
        "processor",
        Topology.MOD_QUEUE,
        Topology.FINAL_QUEUE,
        ProcessorWorkerConfig.class
    );
    when(workerRegistry.all()).thenReturn(List.of(definition));
  }

  @Test
  void onWorkDispatchesToWorkerAndPublishesResult() throws Exception {
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
    verify(controlPlaneRuntime).emitStatusSnapshot();

    Message inbound = new RabbitWorkMessageConverter().toMessage(WorkMessage.text("payload").build());
    adapter.onWork(inbound);

    verify(workerRuntime).dispatch(eq("processorWorker"), any(WorkMessage.class));
    ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(rabbitTemplate).send(eq(Topology.EXCHANGE), eq(Topology.FINAL_QUEUE), messageCaptor.capture());
    assertThat(new String(messageCaptor.getValue().getBody(), StandardCharsets.UTF_8))
        .isEqualTo("processed");
  }

  @Test
  void onControlDelegatesToControlPlaneRuntime() {
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
    verify(controlPlaneRuntime).emitStatusSnapshot();

    adapter.onControl("{}", "processor.control", null);
    verify(controlPlaneRuntime).handle("{}", "processor.control");

    assertThatThrownBy(() -> adapter.onControl(" ", "processor.control", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("payload");

    assertThatThrownBy(() -> adapter.onControl("{}", " ", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("routing key");
  }

  @Test
  void registersListenerAppliesDesiredStateAndEmitsSnapshot() {
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

    ArgumentCaptor<String> beanCaptor = ArgumentCaptor.forClass(String.class);
    verify(controlPlaneRuntime).registerStateListener(beanCaptor.capture(), any());
    assertThat(beanCaptor.getValue()).isEqualTo("processorWorker");
    verify(listenerContainer, times(1)).start();
    verify(controlPlaneRuntime).emitStatusSnapshot();
  }

  @Test
  void emitStatusDeltaDelegatesToControlPlaneRuntime() {
    ProcessorRuntimeAdapter adapter = new ProcessorRuntimeAdapter(
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
