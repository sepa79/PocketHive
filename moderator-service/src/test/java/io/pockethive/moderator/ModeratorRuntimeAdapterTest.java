package io.pockethive.moderator;

import com.rabbitmq.client.Channel;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.spring.WorkerControlPlaneProperties;
import io.pockethive.moderator.shaper.config.PatternConfig;
import io.pockethive.moderator.shaper.config.PatternConfigValidator;
import io.pockethive.moderator.shaper.config.RepeatAlignment;
import io.pockethive.moderator.shaper.config.RepeatConfig;
import io.pockethive.moderator.shaper.config.RepeatUntil;
import io.pockethive.moderator.shaper.config.StepConfig;
import io.pockethive.moderator.shaper.config.StepMode;
import io.pockethive.moderator.shaper.config.StepRangeConfig;
import io.pockethive.moderator.shaper.config.StepRangeUnit;
import io.pockethive.moderator.shaper.config.TransitionConfig;
import io.pockethive.moderator.shaper.config.TransitionType;
import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.autoconfigure.WorkerControlQueueListener;
import io.pockethive.worker.sdk.config.WorkerType;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRegistry;
import io.pockethive.worker.sdk.runtime.WorkerRuntime;
import io.pockethive.worker.sdk.transport.rabbit.RabbitWorkMessageConverter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
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
  private Channel channel;

  private ModeratorDefaults defaults;
  private PatternConfigValidator validator;
  private WorkerDefinition definition;
  private ControlPlaneIdentity identity;

  private static final WorkerControlPlaneProperties WORKER_PROPERTIES =
      ControlPlaneTestFixtures.workerProperties("swarm-alpha", "moderator", "instance-1");
  private static final String IN_QUEUE = WORKER_PROPERTIES.getQueues().get("generator");
  private static final String OUT_QUEUE = WORKER_PROPERTIES.getQueues().get("moderator");
  private static final String EXCHANGE = WORKER_PROPERTIES.getTrafficExchange();

  @BeforeEach
  void setUp() {
    validator = new PatternConfigValidator();
    defaults = new ModeratorDefaults(validator);
    defaults.setEnabled(true);
    identity = new ControlPlaneIdentity(
        WORKER_PROPERTIES.getSwarmId(),
        "moderator",
        WORKER_PROPERTIES.getInstanceId()
    );
    definition = new WorkerDefinition(
        "moderatorWorker",
        ModeratorWorkerImpl.class,
        WorkerType.MESSAGE,
        "moderator",
        IN_QUEUE,
        OUT_QUEUE,
        EXCHANGE,
        ModeratorWorkerConfig.class
    );
  }

  private void stubListenerContainerStopped() {
    lenient().when(listenerRegistry.getListenerContainer("moderatorWorkerListener")).thenReturn(listenerContainer);
    lenient().when(listenerContainer.isRunning()).thenReturn(false);
  }

  @Test
  void onWorkDispatchesToWorkerAndPublishesResult() throws Exception {
    when(workerRegistry.findByRoleAndType("moderator", WorkerType.MESSAGE))
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
        validator
    );

    adapter.initialiseStateListener();
    ArgumentCaptor<Object> defaultConfigCaptor = ArgumentCaptor.forClass(Object.class);
    verify(controlPlaneRuntime).registerDefaultConfig(eq("moderatorWorker"), defaultConfigCaptor.capture());
    assertThat(defaultConfigCaptor.getValue()).isEqualTo(defaults.asConfig());
    verify(controlPlaneRuntime).emitStatusSnapshot();

    Message inbound = new RabbitWorkMessageConverter().toMessage(WorkMessage.text("body").build());
    inbound.getMessageProperties().setDeliveryTag(321L);
    adapter.onWork(inbound, channel, null);

    verify(workerRuntime).dispatch(eq("moderatorWorker"), any(WorkMessage.class));
    ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(rabbitTemplate).send(eq(definition.exchange()), eq(definition.outQueue()), messageCaptor.capture());
    assertThat(new String(messageCaptor.getValue().getBody(), StandardCharsets.UTF_8))
        .isEqualTo("forwarded");
    verify(channel).basicAck(inbound.getMessageProperties().getDeliveryTag(), false);
  }

  @Test
  void onWorkAcknowledgesViaSpringHandleWhenProvided() throws Exception {
    when(workerRegistry.findByRoleAndType("moderator", WorkerType.MESSAGE))
        .thenReturn(Optional.of(definition));
    stubListenerContainerStopped();
    doReturn(WorkResult.none())
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
        validator
    );

    adapter.initialiseStateListener();

    Message inbound = new RabbitWorkMessageConverter().toMessage(WorkMessage.text("body").build());
    TestAcknowledgment acknowledgment = new TestAcknowledgment();

    adapter.onWork(inbound, null, acknowledgment);

    InOrder inOrder = inOrder(workerRuntime);
    inOrder.verify(workerRuntime).dispatch(eq("moderatorWorker"), any(WorkMessage.class));
    assertThat(acknowledgment.acknowledged).isTrue();
    verifyNoInteractions(channel);
  }

  private static final class TestAcknowledgment {

    private boolean acknowledged;

    public void acknowledge() {
      this.acknowledged = true;
    }
  }

  @Test
  void manualAckNotIssuedWhenDispatchFails() throws Exception {
    when(workerRegistry.findByRoleAndType("moderator", WorkerType.MESSAGE))
        .thenReturn(Optional.of(definition));
    stubListenerContainerStopped();
    RuntimeException failure = new RuntimeException("boom");
    doThrow(failure)
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
        validator
    );

    adapter.initialiseStateListener();

    Message inbound = new RabbitWorkMessageConverter().toMessage(WorkMessage.text("body").build());
    inbound.getMessageProperties().setDeliveryTag(654L);
    adapter.onWork(inbound, channel, null);

    verify(channel, never()).basicAck(anyLong(), anyBoolean());
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
    when(workerRegistry.findByRoleAndType("moderator", WorkerType.MESSAGE))
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
        validator
    );

    adapter.initialiseStateListener();
    ArgumentCaptor<Object> defaultConfigCaptor = ArgumentCaptor.forClass(Object.class);
    verify(controlPlaneRuntime).registerDefaultConfig(eq("moderatorWorker"), defaultConfigCaptor.capture());
    assertThat(defaultConfigCaptor.getValue()).isEqualTo(defaults.asConfig());
    ArgumentCaptor<Consumer<WorkerControlPlaneRuntime.WorkerStateSnapshot>> listenerCaptor = ArgumentCaptor.forClass(Consumer.class);
    verify(controlPlaneRuntime).registerStateListener(eq("moderatorWorker"), listenerCaptor.capture());
    verify(listenerContainer, times(1)).start();
    verify(controlPlaneRuntime).emitStatusSnapshot();

    WorkerControlPlaneRuntime.WorkerStateSnapshot snapshot = mock(WorkerControlPlaneRuntime.WorkerStateSnapshot.class);
    when(snapshot.enabled()).thenReturn(Optional.empty());
    ModeratorWorkerConfig currentConfig = defaults.asConfig();
    when(snapshot.config(ModeratorWorkerConfig.class)).thenReturn(Optional.of(new ModeratorWorkerConfig(
        false,
        currentConfig.time(),
        currentConfig.run(),
        currentConfig.pattern(),
        currentConfig.normalization(),
        currentConfig.globalMutators(),
        currentConfig.jitter(),
        currentConfig.seeds()
    )));
    when(listenerContainer.isRunning()).thenReturn(true);

    listenerCaptor.getValue().accept(snapshot);
    verify(listenerContainer).stop();
  }

  @Test
  void onWorkDelegatesErrorsToDispatchHandler() throws Exception {
    when(workerRegistry.findByRoleAndType("moderator", WorkerType.MESSAGE))
        .thenReturn(Optional.of(definition));
    ModeratorRuntimeAdapter adapter = new ModeratorRuntimeAdapter(
        workerRuntime,
        workerRegistry,
        controlPlaneRuntime,
        rabbitTemplate,
        listenerRegistry,
        identity,
        defaults,
        validator
    );

    Message inbound = new RabbitWorkMessageConverter().toMessage(WorkMessage.text("payload").build());
    doThrow(new RuntimeException("boom")).when(workerRuntime)
        .dispatch(eq("moderatorWorker"), any(WorkMessage.class));

    assertThatCode(() -> adapter.onWork(inbound, channel, null)).doesNotThrowAnyException();
  }

  @Test
  void initialiseStateListenerRejectsInvalidDefaults() {
    when(workerRegistry.findByRoleAndType("moderator", WorkerType.MESSAGE))
        .thenReturn(Optional.of(definition));
    stubListenerContainerStopped();

    ModeratorDefaults invalidDefaults = new ModeratorDefaults(validator);
    invalidDefaults.setEnabled(true);
    invalidDefaults.setPattern(new PatternConfig(
        Duration.ofHours(24),
        BigDecimal.valueOf(1000),
        new RepeatConfig(true, RepeatUntil.TOTAL_TIME, null, RepeatAlignment.FROM_START),
        List.of(
            new StepConfig(
                "early",
                new StepRangeConfig(StepRangeUnit.PERCENT, null, null, BigDecimal.ZERO, BigDecimal.valueOf(40)),
                StepMode.FLAT,
                Map.of(),
                List.of(),
                TransitionConfig.none()
            ),
            new StepConfig(
                "late",
                new StepRangeConfig(StepRangeUnit.PERCENT, null, null, BigDecimal.valueOf(60), BigDecimal.valueOf(100)),
                StepMode.FLAT,
                Map.of(),
                List.of(),
                TransitionConfig.none()
            )
        )
    ));

    ModeratorRuntimeAdapter adapter = new ModeratorRuntimeAdapter(
        workerRuntime,
        workerRegistry,
        controlPlaneRuntime,
        rabbitTemplate,
        listenerRegistry,
        identity,
        invalidDefaults,
        validator
    );

    assertThatThrownBy(adapter::initialiseStateListener)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cover");
  }

}
