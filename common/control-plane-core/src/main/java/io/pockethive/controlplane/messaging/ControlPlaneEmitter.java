package io.pockethive.controlplane.messaging;

import io.pockethive.control.AlertMessage;
import io.pockethive.control.CommandResult;
import io.pockethive.control.JournalEvent;
import io.pockethive.control.ConfirmationScope;
import io.pockethive.control.ControlRuntime;
import io.pockethive.control.ControlScope;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.payload.RoleContext;
import io.pockethive.controlplane.payload.StatusPayloadFactory;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.ControlPlaneTopologySettings;
import io.pockethive.controlplane.topology.SwarmControllerControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.WorkerControlPlaneTopologyDescriptor;
import io.pockethive.observability.ControlPlaneJson;
import io.pockethive.observability.StatusEnvelopeBuilder;
import io.pockethive.swarm.model.lifecycle.TerminalResult;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Emits executor results, alerts and status metrics using canonical routing. */
public final class ControlPlaneEmitter {

  private static final Logger log = LoggerFactory.getLogger(ControlPlaneEmitter.class);

  private final ControlPlaneTopologyDescriptor topology;
  private final RoleContext role;
  private final ControlPlanePublisher publisher;
  private final StatusPayloadFactory statusFactory;
  private final Map<String, Object> runtime;

  private ControlPlaneEmitter(
      ControlPlaneTopologyDescriptor topology,
      RoleContext role,
      ControlPlanePublisher publisher,
      StatusPayloadFactory statusFactory,
      Map<String, Object> runtime) {
    this.topology = Objects.requireNonNull(topology, "topology");
    this.role = Objects.requireNonNull(role, "role");
    this.publisher = Objects.requireNonNull(publisher, "publisher");
    this.statusFactory = Objects.requireNonNull(statusFactory, "statusFactory");
    this.runtime = ControlRuntime.normalise(runtime);
    requireRoleMatch();
  }

  public static ControlPlaneEmitter using(
      ControlPlaneTopologyDescriptor topology,
      RoleContext role,
      ControlPlanePublisher publisher,
      Map<String, Object> runtime) {
    return new ControlPlaneEmitter(
        topology,
        role,
        publisher,
        new StatusPayloadFactory(role),
        runtime);
  }

  public static ControlPlaneEmitter worker(
      ControlPlaneIdentity identity,
      ControlPlanePublisher publisher,
      ControlPlaneTopologySettings settings,
      Map<String, Object> runtime) {
    RoleContext role = RoleContext.fromIdentity(identity);
    return using(new WorkerControlPlaneTopologyDescriptor(role.role(), settings), role, publisher, runtime);
  }

  public static ControlPlaneEmitter swarmController(
      ControlPlaneIdentity identity,
      ControlPlanePublisher publisher,
      ControlPlaneTopologySettings settings,
      Map<String, Object> runtime) {
    RoleContext role = requireIdentity(identity, "swarm-controller");
    return using(new SwarmControllerControlPlaneTopologyDescriptor(settings), role, publisher, runtime);
  }

  public void emitResult(ResultContext context) {
    Objects.requireNonNull(context, "context");
    CommandResult result = new CommandResult(
        context.timestamp() == null ? Instant.now() : context.timestamp(),
        io.pockethive.control.ControlPlaneEnvelopeVersion.CURRENT,
        CommandResult.KIND,
        context.signal(),
        role.instanceId(),
        toControlScope(role.toScope()),
        context.correlationId(),
        context.idempotencyKey(),
        runtime,
        context.result());
    ConfirmationScope routingScope = toConfirmationScope(result.scope());
    String routingKey = ControlPlaneRouting.event(CommandResult.KIND, context.signal(), routingScope);
    publisher.publishEvent(new EventMessage(routingKey, serializeEnvelope(result, CommandResult.KIND)));
  }

  public void emitJournal(JournalContext context) {
    Objects.requireNonNull(context, "context");
    JournalEvent event = new JournalEvent(
        context.timestamp() == null ? Instant.now() : context.timestamp(),
        io.pockethive.control.ControlPlaneEnvelopeVersion.CURRENT,
        JournalEvent.KIND,
        context.type(),
        role.instanceId(),
        toControlScope(role.toScope()),
        context.correlationId(),
        context.idempotencyKey(),
        runtime,
        context.data());
    String routingKey = ControlPlaneRouting.event(
        JournalEvent.KIND, event.type(), toConfirmationScope(event.scope()));
    publisher.publishEvent(new EventMessage(routingKey, serializeEnvelope(event, JournalEvent.KIND)));
  }

  public void emitFailure(FailureContext context) {
    Objects.requireNonNull(context, "context");
    log.warn(
        "[CTRL] command failure operation={} phase={} code={} message={} swarmId={} role={} instance={} correlationId={} idempotencyKey={} retryable={} errorType={} errorDetail={}",
        context.signal(), context.phase(), context.code(), context.message(), role.swarmId(), role.role(),
        role.instanceId(), context.correlationId(), context.idempotencyKey(), context.result().retryable(),
        context.errorType(), context.errorDetail());
    emitResult(new ResultContext(
        context.signal(),
        context.correlationId(),
        context.idempotencyKey(),
        context.result(),
        context.timestamp()));
    publishAlertFromFailure(context);
  }

  public void emitStatusSnapshot(StatusContext context) {
    publishStatus("status-full", context);
  }

  public void emitStatusDelta(StatusContext context) {
    publishStatus("status-delta", context);
  }

  private void publishStatus(String type, StatusContext context) {
    Objects.requireNonNull(context, "context");
    String routingKey = ControlPlaneRouting.event("metric", type, role.toScope());
    Consumer<StatusEnvelopeBuilder> customiser = builder -> {
      builder.controlOut(routingKey);
      builder.runtime(runtime);
      context.customiser().accept(builder);
    };
    String payload = switch (type) {
      case "status-full" -> statusFactory.snapshot(customiser);
      case "status-delta" -> statusFactory.delta(customiser);
      default -> throw new IllegalArgumentException("Unsupported status type: " + type);
    };
    publisher.publishEvent(new EventMessage(routingKey, payload));
  }

  private void publishAlertFromFailure(FailureContext context) {
    Map<String, Object> alertContext = new LinkedHashMap<>();
    alertContext.put("phase", context.phase());
    alertContext.putAll(context.result().context());
    AlertMessage alert = Alerts.error(
        role.instanceId(),
        toControlScope(role.toScope()),
        context.correlationId(),
        context.idempotencyKey(),
        runtime,
        context.code(),
        context.message(),
        context.errorType(),
        context.errorDetail(),
        context.logRef(),
        Map.copyOf(alertContext),
        context.timestamp());
    publishAlert(alert);
  }

  public void publishAlert(AlertMessage alert) {
    Objects.requireNonNull(alert, "alert");
    ConfirmationScope routingScope = toConfirmationScope(alert.scope());
    String routingKey = ControlPlaneRouting.event("alert", "alert", routingScope);
    publisher.publishEvent(new EventMessage(routingKey, serializeEnvelope(alert, "alert")));
  }

  private void requireRoleMatch() {
    if (!topology.role().equals(role.role())) {
      throw new IllegalArgumentException(
          "Role context does not match topology role: expected " + topology.role() + " but was " + role.role());
    }
  }

  private static RoleContext requireIdentity(ControlPlaneIdentity identity, String expectedRole) {
    RoleContext role = RoleContext.fromIdentity(Objects.requireNonNull(identity, "identity"));
    if (!expectedRole.equals(role.role())) {
      throw new IllegalArgumentException(
          "Identity role mismatch: expected " + expectedRole + " but was " + role.role());
    }
    return role;
  }

  private static String serializeEnvelope(Object value, String label) {
    return ControlPlaneJson.write(value, label + " envelope");
  }

  private static ConfirmationScope toConfirmationScope(ControlScope scope) {
    return new ConfirmationScope(scope.swarmId(), scope.role(), scope.instance());
  }

  private static ControlScope toControlScope(ConfirmationScope scope) {
    return new ControlScope(scope.swarmId(), scope.role(), scope.instance());
  }

  private static String requireText(String field, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value.trim();
  }

  public record ResultContext(
      String signal,
      String correlationId,
      String idempotencyKey,
      TerminalResult result,
      Instant timestamp
  ) {
    public ResultContext {
      signal = requireText("signal", signal);
      correlationId = requireText("correlationId", correlationId);
      idempotencyKey = requireText("idempotencyKey", idempotencyKey);
      result = Objects.requireNonNull(result, "result");
    }
  }

  public record JournalContext(
      String type,
      String correlationId,
      String idempotencyKey,
      Map<String, Object> data,
      Instant timestamp) {
    public JournalContext {
      type = requireText("type", type);
      correlationId = requireText("correlationId", correlationId);
      idempotencyKey = requireText("idempotencyKey", idempotencyKey);
      data = data == null ? Map.of() : Map.copyOf(data);
    }
  }

  public record FailureContext(
      String signal,
      String correlationId,
      String idempotencyKey,
      TerminalResult result,
      String phase,
      String code,
      String message,
      String errorType,
      String errorDetail,
      String logRef,
      Instant timestamp
  ) {
    public FailureContext {
      signal = requireText("signal", signal);
      correlationId = requireText("correlationId", correlationId);
      idempotencyKey = requireText("idempotencyKey", idempotencyKey);
      result = Objects.requireNonNull(result, "result");
      phase = requireText("phase", phase);
      code = requireText("code", code);
      message = requireText("message", message);
      errorType = optionalText(errorType);
      errorDetail = optionalText(errorDetail);
      logRef = optionalText(logRef);
    }

    public static FailureContext fromException(
        String signal,
        String correlationId,
        String idempotencyKey,
        TerminalResult result,
        String phase,
        Throwable exception,
        String logRef,
        Instant timestamp) {
      Objects.requireNonNull(exception, "exception");
      String detail = optionalText(exception.getMessage());
      return new FailureContext(
          signal,
          correlationId,
          idempotencyKey,
          result,
          phase,
          Alerts.Codes.RUNTIME_EXCEPTION,
          detail == null ? exception.getClass().getName() : detail,
          exception.getClass().getName(),
          detail,
          logRef,
          timestamp);
    }
  }

  public record StatusContext(Consumer<StatusEnvelopeBuilder> customiser) {
    public StatusContext {
      customiser = Objects.requireNonNull(customiser, "customiser");
    }

    public static StatusContext of(Consumer<StatusEnvelopeBuilder> customiser) {
      return new StatusContext(customiser);
    }
  }

  private static String optionalText(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
