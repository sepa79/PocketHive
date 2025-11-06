package io.pockethive.generator;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.spring.WorkerControlPlaneProperties;
import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.input.WorkInput;
import io.pockethive.worker.sdk.input.WorkInputRegistry;
import io.pockethive.worker.sdk.input.SchedulerWorkInput;
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

import io.pockethive.worker.sdk.testing.ControlPlaneTestFixtures;
import static org.assertj.core.api.Assertions.assertThat;
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

  @Mock
  private WorkInputRegistry workInputRegistry;

  private GeneratorDefaults defaults;
  private WorkerDefinition definition;
  private ControlPlaneIdentity identity;

  private static final WorkerControlPlaneProperties WORKER_PROPERTIES =
      ControlPlaneTestFixtures.workerProperties("swarm-alpha", "generator", "instance-1");
  private static final String EXCHANGE = WORKER_PROPERTIES.getTrafficExchange();
  private static final String OUT_QUEUE = WORKER_PROPERTIES.getQueues().get("generator");

  @BeforeEach
  void setUp() {
    MessageConfig messageConfig = new MessageConfig();
    messageConfig.setPath("/default");
    messageConfig.setMethod("GET");
    messageConfig.setBody("{}");
    defaults = new GeneratorDefaults(messageConfig);
    defaults.setRatePerSec(2.0);
    defaults.setEnabled(true);
    identity = new ControlPlaneIdentity(
        WORKER_PROPERTIES.getSwarmId(),
        "generator",
        WORKER_PROPERTIES.getInstanceId()
    );
    definition = new WorkerDefinition(
        "generatorWorker",
        GeneratorWorkerImpl.class,
        WorkerInputType.SCHEDULER,
        "generator",
        null,
        OUT_QUEUE,
        EXCHANGE,
        GeneratorWorkerConfig.class
    );
  }

  @Test
  void tickDispatchesUsingDefaultRate() throws Exception {
    when(workerRegistry.streamByRoleAndInput("generator", WorkerInputType.SCHEDULER))
        .thenAnswer(invocation -> java.util.stream.Stream.of(definition));
    doReturn(WorkResult.message(WorkMessage.text("payload").build()))
        .when(workerRuntime)
        .dispatch(eq("generatorWorker"), any(WorkMessage.class));

    GeneratorRuntimeAdapter adapter = new GeneratorRuntimeAdapter(
        workerRuntime,
        workerRegistry,
        controlPlaneRuntime,
        rabbitTemplate,
        identity,
        defaults,
        workInputRegistry
    );
    adapter.start();
    adapter.tick();
    ArgumentCaptor<WorkMessage> workMessageCaptor = ArgumentCaptor.forClass(WorkMessage.class);
    verify(workerRuntime, times(2)).dispatch(eq("generatorWorker"), workMessageCaptor.capture());
    assertThat(workMessageCaptor.getAllValues())
        .allSatisfy(message -> {
          assertThat(message.headers()).containsEntry("swarmId", identity.swarmId());
          assertThat(message.headers()).containsEntry("instanceId", identity.instanceId());
        });
    ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(rabbitTemplate, times(2)).send(eq(definition.exchange()), eq(definition.outQueue()), messageCaptor.capture());
    assertThat(messageCaptor.getAllValues())
        .allSatisfy(message -> assertThat(new String(message.getBody(), StandardCharsets.UTF_8)).isEqualTo("payload"));
    verify(controlPlaneRuntime).emitStatusSnapshot();
  }

  @Test
  void registersStateListenerForEachGeneratorWorker() {
    when(workerRegistry.streamByRoleAndInput("generator", WorkerInputType.SCHEDULER))
        .thenAnswer(invocation -> java.util.stream.Stream.of(definition));
    GeneratorRuntimeAdapter adapter = new GeneratorRuntimeAdapter(
        workerRuntime,
        workerRegistry,
        controlPlaneRuntime,
        rabbitTemplate,
        identity,
        defaults,
        workInputRegistry
    );

    adapter.start();

    ArgumentCaptor<WorkInput> workInputCaptor = ArgumentCaptor.forClass(WorkInput.class);
    verify(workInputRegistry).register(eq(definition), workInputCaptor.capture());
    assertThat(workInputCaptor.getValue()).isInstanceOf(SchedulerWorkInput.class);
    ArgumentCaptor<String> beanCaptor = ArgumentCaptor.forClass(String.class);
    InOrder inOrder = Mockito.inOrder(controlPlaneRuntime);
    inOrder.verify(controlPlaneRuntime).registerDefaultConfig(eq("generatorWorker"), any());
    inOrder.verify(controlPlaneRuntime).registerStateListener(beanCaptor.capture(), any());
    assertThat(beanCaptor.getValue()).isEqualTo("generatorWorker");
  }
}
