package io.pockethive.postprocessor;

import io.pockethive.Topology;
import io.pockethive.TopologyDefaults;
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
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostProcessorRuntimeAdapterTest {

  @Mock
  private WorkerRuntime workerRuntime;

  @Mock
  private WorkerRegistry workerRegistry;

  @Mock
  private WorkerControlPlaneRuntime controlPlaneRuntime;

  @Mock
  private RabbitListenerEndpointRegistry listenerRegistry;

  @Mock
  private MessageListenerContainer listenerContainer;

  private PostProcessorDefaults defaults;
  private WorkerDefinition definition;
  private ControlPlaneIdentity identity;

  @BeforeEach
  void setUp() {
    defaults = new PostProcessorDefaults();
    defaults.setEnabled(true);
    identity = new ControlPlaneIdentity(Topology.SWARM_ID, "postprocessor", "instance-1");
    definition = new WorkerDefinition(
        "postProcessorWorker",
        PostProcessorWorkerImpl.class,
        WorkerType.MESSAGE,
        "postprocessor",
        Topology.FINAL_QUEUE,
        null,
        PostProcessorWorkerConfig.class
    );
  }

  @Test
  void onWorkDispatchesToWorker() throws Exception {
    when(workerRegistry.findByRoleAndType("postprocessor", WorkerType.MESSAGE))
        .thenReturn(Optional.of(definition));
    lenient().when(listenerRegistry.getListenerContainer("postProcessorWorkerListener"))
        .thenReturn(listenerContainer);
    lenient().when(listenerContainer.isRunning()).thenReturn(false);

    doReturn(WorkResult.none()).when(workerRuntime).dispatch(eq("postProcessorWorker"), any(WorkMessage.class));

    PostProcessorRuntimeAdapter adapter = new PostProcessorRuntimeAdapter(
        workerRuntime,
        workerRegistry,
        controlPlaneRuntime,
        listenerRegistry,
        identity,
        defaults
    );

    adapter.initialiseStateListener();
    verify(controlPlaneRuntime).registerDefaultConfig(eq("postProcessorWorker"), any());
    verify(controlPlaneRuntime).emitStatusSnapshot();

    Message inbound = new RabbitWorkMessageConverter().toMessage(WorkMessage.text("payload").build());
    adapter.onWork(inbound);

    verify(workerRuntime).dispatch(eq("postProcessorWorker"), any(WorkMessage.class));
  }

  @Test
  void controlQueueListenerDelegatesToControlPlaneRuntime() {
    WorkerControlQueueListener listener = new WorkerControlQueueListener(controlPlaneRuntime);

    listener.onControl("{}", TopologyDefaults.FINAL_QUEUE + ".control", null);
    verify(controlPlaneRuntime).handle("{}", TopologyDefaults.FINAL_QUEUE + ".control");

    assertThatThrownBy(() -> listener.onControl(" ", "rk", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("payload");

    assertThatThrownBy(() -> listener.onControl("{}", " ", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("routing key");
  }

  @Test
  void registersStateListenerAndAppliesDesiredState() {
    when(workerRegistry.findByRoleAndType("postprocessor", WorkerType.MESSAGE))
        .thenReturn(Optional.of(definition));
    lenient().when(listenerRegistry.getListenerContainer("postProcessorWorkerListener"))
        .thenReturn(listenerContainer);
    lenient().when(listenerContainer.isRunning()).thenReturn(false);

    PostProcessorRuntimeAdapter adapter = new PostProcessorRuntimeAdapter(
        workerRuntime,
        workerRegistry,
        controlPlaneRuntime,
        listenerRegistry,
        identity,
        defaults
    );

    adapter.initialiseStateListener();
    verify(controlPlaneRuntime).registerDefaultConfig(eq("postProcessorWorker"), any());
    verify(controlPlaneRuntime).emitStatusSnapshot();

    ArgumentCaptor<String> beanCaptor = ArgumentCaptor.forClass(String.class);
    verify(controlPlaneRuntime).registerStateListener(beanCaptor.capture(), any());
    verify(listenerContainer, times(1)).start();
    assertThat(beanCaptor.getValue()).isEqualTo("postProcessorWorker");
  }

}
