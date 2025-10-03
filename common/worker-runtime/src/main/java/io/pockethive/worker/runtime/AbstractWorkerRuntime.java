package io.pockethive.worker.runtime;

import io.pockethive.control.ConfirmationScope;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.topology.ControlPlaneRouteCatalog;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.ControlQueueDescriptor;
import io.pockethive.controlplane.worker.WorkerConfigCommand;
import io.pockethive.controlplane.worker.WorkerControlPlane;
import io.pockethive.controlplane.worker.WorkerSignalListener;
import io.pockethive.controlplane.worker.WorkerSignalListener.WorkerSignalContext;
import io.pockethive.controlplane.worker.WorkerStatusRequest;
import io.pockethive.observability.StatusEnvelopeBuilder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.MDC;

/**
 * Shared runtime wiring for PocketHive worker services.
 */
public abstract class AbstractWorkerRuntime {

  private final Logger log;
  private final ControlPlaneEmitter controlEmitter;
  private final WorkerControlPlane controlPlane;
  private final ControlPlaneIdentity identity;
  private final ConfirmationScope confirmationScope;
  private final String controlQueueName;
  private final List<String> controlRoutes;
  private final WorkerSignalListener controlListener;

  protected AbstractWorkerRuntime(Logger log,
                                  ControlPlaneEmitter controlEmitter,
                                  WorkerControlPlane controlPlane,
                                  ControlPlaneIdentity identity,
                                  ControlPlaneTopologyDescriptor topology) {
    this.log = Objects.requireNonNull(log, "log");
    this.controlEmitter = Objects.requireNonNull(controlEmitter, "controlEmitter");
    this.controlPlane = Objects.requireNonNull(controlPlane, "controlPlane");
    this.identity = Objects.requireNonNull(identity, "identity");
    ControlPlaneTopologyDescriptor descriptor = Objects.requireNonNull(topology, "topology");
    this.confirmationScope = new ConfirmationScope(identity.swarmId(), identity.role(), identity.instanceId());
    this.controlQueueName = resolveControlQueue(descriptor, identity.instanceId());
    this.controlRoutes = List.of(resolveRoutes(descriptor, identity));
    this.controlListener = createListener();
  }

  private static String resolveControlQueue(ControlPlaneTopologyDescriptor descriptor, String instanceId) {
    Optional<ControlQueueDescriptor> queue = descriptor.controlQueue(instanceId);
    return queue.map(ControlQueueDescriptor::name)
        .orElseThrow(() -> new IllegalStateException("Control queue descriptor is missing for instance " + instanceId));
  }

  private WorkerSignalListener createListener() {
    return new WorkerSignalListener() {
      @Override
      public void onStatusRequest(WorkerStatusRequest request) {
        withSignalContext(request.signal(), () -> {
          logControlReceive(request.envelope().routingKey(),
              request.signal() != null ? request.signal().signal() : null,
              request.payload());
          handleStatusRequest(request);
        });
      }

      @Override
      public void onConfigUpdate(WorkerConfigCommand command) {
        withSignalContext(command.signal(), () -> {
          logControlReceive(command.envelope().routingKey(),
              command.signal() != null ? command.signal().signal() : null,
              command.payload());
          handleConfigUpdate(command);
        });
      }

      @Override
      public void onUnsupported(WorkerSignalContext context) {
        log.debug("Ignoring unsupported control signal {}", context.envelope().signal().signal());
      }
    };
  }

  private void withSignalContext(ControlSignal signal, Runnable action) {
    if (signal != null) {
      putIfText("correlation_id", signal.correlationId());
      putIfText("idempotency_key", signal.idempotencyKey());
    }
    try {
      action.run();
    } finally {
      MDC.remove("correlation_id");
      MDC.remove("idempotency_key");
    }
  }

  private void putIfText(String key, String value) {
    if (value != null && !value.isBlank()) {
      MDC.put(key, value);
    }
  }

  protected void handleStatusRequest(WorkerStatusRequest request) {
    sendStatusFull(0);
  }

  protected void handleConfigUpdate(WorkerConfigCommand command) {
    // no-op by default; subclasses override as needed
  }

  protected final WorkerSignalListener controlListener() {
    return controlListener;
  }

  protected final WorkerControlPlane controlPlane() {
    return controlPlane;
  }

  protected final ControlPlaneEmitter controlEmitter() {
    return controlEmitter;
  }

  protected final ControlPlaneIdentity identity() {
    return identity;
  }

  protected final ConfirmationScope confirmationScope() {
    return confirmationScope;
  }

  protected final String controlQueueName() {
    return controlQueueName;
  }

  protected final List<String> controlRoutes() {
    return controlRoutes;
  }

  protected ControlPlaneEmitter.StatusContext statusContext(long tps) {
    return baseStatusContext(tps, builder -> { });
  }

  protected final ControlPlaneEmitter.StatusContext baseStatusContext(long tps,
                                                                      Consumer<StatusEnvelopeBuilder> customiser) {
    Objects.requireNonNull(customiser, "customiser");
    return ControlPlaneEmitter.StatusContext.of(builder -> {
      builder.controlIn(controlQueueName);
      builder.controlRoutes(controlRoutes.toArray(String[]::new));
      builder.tps(tps);
      customiser.accept(builder);
    });
  }

  protected String statusLogDetails(long tps) {
    return "tps=" + tps;
  }

  protected final void sendStatusDelta(long tps) {
    String routingKey = ControlPlaneRouting.event("status-delta", confirmationScope);
    logControlSend(routingKey, statusLogDetails(tps));
    controlEmitter.emitStatusDelta(statusContext(tps));
  }

  protected final void sendStatusFull(long tps) {
    String routingKey = ControlPlaneRouting.event("status-full", confirmationScope);
    logControlSend(routingKey, statusLogDetails(tps));
    controlEmitter.emitStatusSnapshot(statusContext(tps));
  }

  protected final ListenerLifecycle listenerLifecycle(Runnable enableAction, Runnable disableAction) {
    Objects.requireNonNull(enableAction, "enableAction");
    Objects.requireNonNull(disableAction, "disableAction");
    return new ListenerLifecycle(enableAction, disableAction);
  }

  protected final void logControlReceive(String routingKey, String signal, String payload) {
    String snippet = snippet(payload);
    if (signal != null && signal.startsWith("status")) {
      log.debug("[CTRL] RECV rk={} inst={} payload={}", routingKey, identity.instanceId(), snippet);
    } else if ("config-update".equals(signal)) {
      log.info("[CTRL] RECV rk={} inst={} payload={}", routingKey, identity.instanceId(), snippet);
    } else {
      log.info("[CTRL] RECV rk={} inst={} payload={}", routingKey, identity.instanceId(), snippet);
    }
  }

  protected final void logControlSend(String routingKey, String details) {
    String snippet = details == null ? "" : details;
    if (routingKey.contains(".status-")) {
      log.debug("[CTRL] SEND rk={} inst={} {}", routingKey, identity.instanceId(), snippet);
    } else if (routingKey.contains(".config-update.")) {
      log.info("[CTRL] SEND rk={} inst={} {}", routingKey, identity.instanceId(), snippet);
    } else {
      log.info("[CTRL] SEND rk={} inst={} {}", routingKey, identity.instanceId(), snippet);
    }
  }

  protected static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be null or blank");
    }
    return value;
  }

  protected static String snippet(String payload) {
    if (payload == null) {
      return "";
    }
    String trimmed = payload.strip();
    if (trimmed.length() > 300) {
      return trimmed.substring(0, 300) + "…";
    }
    return trimmed;
  }

  protected static String[] resolveRoutes(ControlPlaneTopologyDescriptor descriptor, ControlPlaneIdentity identity) {
    Objects.requireNonNull(descriptor, "descriptor");
    Objects.requireNonNull(identity, "identity");
    ControlPlaneRouteCatalog routes = descriptor.routes();
    List<String> resolved = new ArrayList<>();
    resolved.addAll(expandRoutes(routes.configSignals(), identity));
    resolved.addAll(expandRoutes(routes.statusSignals(), identity));
    resolved.addAll(expandRoutes(routes.lifecycleSignals(), identity));
    resolved.addAll(expandRoutes(routes.statusEvents(), identity));
    resolved.addAll(expandRoutes(routes.lifecycleEvents(), identity));
    resolved.addAll(expandRoutes(routes.otherEvents(), identity));
    LinkedHashSet<String> unique = resolved.stream()
        .filter(route -> route != null && !route.isBlank())
        .collect(Collectors.toCollection(LinkedHashSet::new));
    return unique.toArray(String[]::new);
  }

  private static Collection<String> expandRoutes(Set<String> templates, ControlPlaneIdentity identity) {
    if (templates == null || templates.isEmpty()) {
      return List.of();
    }
    String instanceId = identity.instanceId();
    return templates.stream()
        .filter(Objects::nonNull)
        .map(route -> route.replace(ControlPlaneRouteCatalog.INSTANCE_TOKEN, instanceId))
        .toList();
  }

  public static final class ListenerLifecycle {

    private final Runnable enableAction;
    private final Runnable disableAction;

    private ListenerLifecycle(Runnable enableAction, Runnable disableAction) {
      this.enableAction = enableAction;
      this.disableAction = disableAction;
    }

    public void apply(boolean enabled) {
      if (enabled) {
        enable();
      } else {
        disable();
      }
    }

    public void enable() {
      enableAction.run();
    }

    public void disable() {
      disableAction.run();
    }
  }
}
