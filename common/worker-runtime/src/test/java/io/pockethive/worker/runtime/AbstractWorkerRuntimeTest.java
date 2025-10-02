package io.pockethive.worker.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter;
import io.pockethive.controlplane.consumer.ControlSignalEnvelope;
import io.pockethive.controlplane.topology.ControlPlaneRouteCatalog;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.ControlQueueDescriptor;
import io.pockethive.controlplane.worker.WorkerConfigCommand;
import io.pockethive.controlplane.worker.WorkerControlPlane;
import io.pockethive.controlplane.worker.WorkerSignalListener;
import io.pockethive.controlplane.worker.WorkerStatusRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

@ExtendWith(MockitoExtension.class)
class AbstractWorkerRuntimeTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Mock
  private ControlPlaneEmitter emitter;

  @Mock
  private WorkerControlPlane controlPlane;

  private Logger logger;
  private TestRuntime runtime;
  private ControlPlaneIdentity identity;

  @BeforeEach
  void setUp() {
    identity = new ControlPlaneIdentity("swarm-1", "test-role", "instance-A");
    logger = (Logger) LoggerFactory.getLogger("test-runtime");
    logger.setLevel(Level.DEBUG);
    runtime = new TestRuntime(logger, emitter, controlPlane, identity, new TestTopologyDescriptor());
  }

  @AfterEach
  void tearDown() {
    MDC.clear();
  }

  @Test
  void listenerDispatchesAndClearsMdc() {
    WorkerSignalListener listener = runtime.listener();
    ControlSignal statusSignal = ControlSignal.forInstance("status-request", identity.swarmId(),
        identity.role(), identity.instanceId(), "corr-1", "idem-1", Map.of());
    ControlSignalEnvelope statusEnvelope = new ControlSignalEnvelope(statusSignal,
        "sig.status-request.swarm-1.test-role.instance-A", "{}", Instant.now());
    listener.onStatusRequest(new WorkerStatusRequest(statusEnvelope, "{}"));

    assertThat(runtime.statusRequests).isEqualTo(1);
    assertThat(MDC.get("correlation_id")).isNull();
    assertThat(MDC.get("idempotency_key")).isNull();

    ControlSignal configSignal = ControlSignal.forInstance("config-update", identity.swarmId(),
        identity.role(), identity.instanceId(), "corr-2", "idem-2", Map.of("enabled", true));
    ControlSignalEnvelope configEnvelope = new ControlSignalEnvelope(configSignal,
        "sig.config-update.swarm-1.test-role.instance-A", "{\"enabled\":true}", Instant.now());
    WorkerConfigCommand command = WorkerConfigCommand.from(configEnvelope,
        "{\"enabled\":true}", MAPPER);
    listener.onConfigUpdate(command);

    assertThat(runtime.configUpdates).isEqualTo(1);
    assertThat(MDC.get("correlation_id")).isNull();
    assertThat(MDC.get("idempotency_key")).isNull();
  }

  @Test
  void resolveRoutesExpandsTemplatesAndDeduplicates() {
    assertThat(runtime.controlRoutes())
        .containsExactlyInAnyOrder(
            "sig.config.instance-A",
            "sig.status.instance-A",
            "ev.lifecycle.instance-A",
            "ev.status.instance-A",
            "ev.other.fixed"
        );
  }

  @Test
  void loggingAdjustsLevelByMessageType() {
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);

    runtime.logReceiveForTest("rk.status", "status-request", "{\"hello\":true}");
    runtime.logReceiveForTest("rk.config", "config-update", "{\"enabled\":true}");
    runtime.logReceiveForTest("rk.ready", "swarm-start", "{\"signal\":\"swarm-start\"}");
    runtime.logSendForTest("ev.status-delta.swarm", "tps=5");
    runtime.logSendForTest("ev.config-update.swarm", "result=ok");
    runtime.logSendForTest("ev.ready.swarm", "result=success");

    List<ILoggingEvent> events = appender.list;
    assertThat(events).hasSize(6);
    assertThat(events.get(0).getLevel()).isEqualTo(Level.DEBUG);
    assertThat(events.get(1).getLevel()).isEqualTo(Level.INFO);
    assertThat(events.get(2).getLevel()).isEqualTo(Level.INFO);
    assertThat(events.get(3).getLevel()).isEqualTo(Level.DEBUG);
    assertThat(events.get(4).getLevel()).isEqualTo(Level.INFO);
    assertThat(events.get(5).getLevel()).isEqualTo(Level.INFO);

    logger.detachAppender(appender);
  }

  @Test
  void sendStatusDelegatesToEmitter() {
    runtime.sendDeltaForTest(9);
    runtime.sendFullForTest(12);

    verify(emitter).emitStatusDelta(any());
    verify(emitter).emitStatusSnapshot(any());
  }

  @Test
  void listenerLifecycleExecutesSuppliedActions() {
    StringBuilder calls = new StringBuilder();
    AbstractWorkerRuntime.ListenerLifecycle lifecycle = runtime.createLifecycle(
        () -> calls.append("start"),
        () -> calls.append("stop"));

    lifecycle.enable();
    lifecycle.disable();
    lifecycle.apply(true);
    lifecycle.apply(false);

    assertThat(calls.toString()).isEqualTo("startstopstartstop");
  }

  private static final class TestTopologyDescriptor implements ControlPlaneTopologyDescriptor {

    @Override
    public String role() {
      return "test-role";
    }

    @Override
    public Optional<ControlQueueDescriptor> controlQueue(String instanceId) {
      return Optional.of(new ControlQueueDescriptor("queue-" + instanceId,
          Set.of("sig.config." + instanceId), Set.of("ev.status." + instanceId)));
    }

    @Override
    public ControlPlaneRouteCatalog routes() {
      return new ControlPlaneRouteCatalog(
          Set.of("sig.config.{instance}"),
          Set.of("sig.status.{instance}"),
          Set.of("ev.lifecycle.{instance}"),
          Set.of("ev.status.{instance}"),
          Set.of("ev.other.fixed"),
          Set.of("ev.lifecycle.{instance}")
      );
    }
  }

  private static final class TestRuntime extends AbstractWorkerRuntime {

    private int statusRequests;
    private int configUpdates;

    private TestRuntime(Logger log,
                        ControlPlaneEmitter controlEmitter,
                        WorkerControlPlane controlPlane,
                        ControlPlaneIdentity identity,
                        ControlPlaneTopologyDescriptor topology) {
      super(log, controlEmitter, controlPlane, identity, topology);
    }

    @Override
    protected void handleStatusRequest(WorkerStatusRequest request) {
      statusRequests++;
    }

    @Override
    protected void handleConfigUpdate(WorkerConfigCommand command) {
      configUpdates++;
    }

    @Override
    protected ControlPlaneEmitter.StatusContext statusContext(long tps) {
      return baseStatusContext(tps, builder -> builder.controlIn("queue:" + controlQueueName()));
    }

    private WorkerSignalListener listener() {
      return controlListener();
    }

    private void logReceiveForTest(String routingKey, String signal, String payload) {
      logControlReceive(routingKey, signal, payload);
    }

    private void logSendForTest(String routingKey, String details) {
      logControlSend(routingKey, details);
    }

    private void sendDeltaForTest(long tps) {
      sendStatusDelta(tps);
    }

    private void sendFullForTest(long tps) {
      sendStatusFull(tps);
    }

    private AbstractWorkerRuntime.ListenerLifecycle createLifecycle(Runnable start, Runnable stop) {
      return listenerLifecycle(start, stop);
    }
  }
}
