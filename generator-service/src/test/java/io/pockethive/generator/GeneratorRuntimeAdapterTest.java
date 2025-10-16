package io.pockethive.generator;

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
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GeneratorRuntimeAdapterTest {

  @Mock
  private WorkerRuntime workerRuntime;

  @Mock
  private WorkerRegistry workerRegistry;

  @Mock
  private WorkerControlPlaneRuntime controlPlaneRuntime;

  @Mock
  private RabbitTemplate rabbitTemplate;

  private GeneratorDefaults defaults;
  private WorkerDefinition definition;
  private ControlPlaneIdentity identity;

  @BeforeEach
  void setUp() {
    MessageConfig messageConfig = new MessageConfig();
    messageConfig.setPath("/default");
    messageConfig.setMethod("GET");
    messageConfig.setBody("{}");
    defaults = new GeneratorDefaults(messageConfig);
    defaults.setRatePerSec(2.0);
    defaults.setEnabled(true);
    identity = new ControlPlaneIdentity(Topology.SWARM_ID, "generator", "instance-1");
    definition = new WorkerDefinition(
        "generatorWorker",
        GeneratorWorkerImpl.class,
        WorkerType.GENERATOR,
        "generator",
        null,
        TopologyDefaults.GEN_QUEUE,
        GeneratorWorkerConfig.class
    );
  }

  @Test
  void tickDispatchesUsingDefaultRate() throws Exception {
    when(workerRegistry.all()).thenReturn(List.of(definition));
    doReturn(WorkResult.message(WorkMessage.text("payload").build()))
        .when(workerRuntime)
        .dispatch(eq("generatorWorker"), any(WorkMessage.class));

    GeneratorRuntimeAdapter adapter = new GeneratorRuntimeAdapter(
        workerRuntime,
        workerRegistry,
        controlPlaneRuntime,
        rabbitTemplate,
        identity,
        defaults
    );

    adapter.emitInitialStatus();
    adapter.tick();
    adapter.emitStatusDelta();

    verify(workerRuntime, times(2)).dispatch(eq("generatorWorker"), any(WorkMessage.class));
    ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(rabbitTemplate, times(2)).send(eq(Topology.EXCHANGE), eq(Topology.GEN_QUEUE), messageCaptor.capture());
    assertThat(messageCaptor.getAllValues())
        .allSatisfy(message -> assertThat(new String(message.getBody(), StandardCharsets.UTF_8)).isEqualTo("payload"));
    verify(controlPlaneRuntime).emitStatusSnapshot();
    verify(controlPlaneRuntime).emitStatusDelta();
  }

  @Test
  void controlQueueListenerDelegatesToControlPlaneRuntime() {
    WorkerControlQueueListener listener = new WorkerControlQueueListener(controlPlaneRuntime);

    listener.onControl("{}", "generator.control", null);
    verify(controlPlaneRuntime).handle("{}", "generator.control");

    assertThatThrownBy(() -> listener.onControl(" ", "generator.control", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("payload");

    assertThatThrownBy(() -> listener.onControl("{}", " ", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("routing key");
  }

  @Test
  void registersStateListenerForEachGeneratorWorker() {
    when(workerRegistry.all()).thenReturn(List.of(definition));
    new GeneratorRuntimeAdapter(
        workerRuntime,
        workerRegistry,
        controlPlaneRuntime,
        rabbitTemplate,
        identity,
        defaults
    );

    ArgumentCaptor<String> beanCaptor = ArgumentCaptor.forClass(String.class);
    InOrder inOrder = Mockito.inOrder(controlPlaneRuntime);
    inOrder.verify(controlPlaneRuntime).registerDefaultConfig(eq("generatorWorker"), any());
    inOrder.verify(controlPlaneRuntime).registerStateListener(beanCaptor.capture(), any());
    assertThat(beanCaptor.getValue()).isEqualTo("generatorWorker");
  }
}
