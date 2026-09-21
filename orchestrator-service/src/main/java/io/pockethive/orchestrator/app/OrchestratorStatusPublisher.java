package io.pockethive.orchestrator.app;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter;
import io.pockethive.controlplane.topology.ControlPlaneRouteCatalog;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.orchestrator.domain.SwarmStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Responsibility: Publish the Orchestrator's periodic status delta and requested full status snapshot.
 * Must not: Consume control-plane messages, mutate swarm state, or decide lifecycle outcomes.
 * Contract: Build status only from the canonical topology, current swarm registry, and compute adapter state.
 */
@Component
public class OrchestratorStatusPublisher {

  private static final long STATUS_INTERVAL_MS = 5_000L;

  private final SwarmStore store;
  private final ContainerLifecycleManager lifecycle;
  private final ControlPlaneEmitter emitter;
  private final String controlQueue;
  private final List<String> controlRoutes;
  private final Instant startedAt = Instant.now();

  public OrchestratorStatusPublisher(
      SwarmStore store,
      ContainerLifecycleManager lifecycle,
      ControlPlaneEmitter emitter,
      @Qualifier("managerControlPlaneIdentity") ControlPlaneIdentity identity,
      @Qualifier("managerControlPlaneTopologyDescriptor") ControlPlaneTopologyDescriptor descriptor,
      @Qualifier("managerControlQueueName") String controlQueue) {
    this.store = Objects.requireNonNull(store, "store");
    this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    this.emitter = Objects.requireNonNull(emitter, "emitter");
    String instanceId = Objects.requireNonNull(identity, "identity").instanceId();
    this.controlQueue = requireText("controlQueue", controlQueue);
    this.controlRoutes = resolveControlRoutes(
        Objects.requireNonNull(descriptor, "descriptor").routes(), instanceId);
  }

  @Scheduled(fixedRate = STATUS_INTERVAL_MS)
  public void publishDelta() {
    emitter.emitStatusDelta(ControlPlaneEmitter.StatusContext.of(builder ->
        builder.workPlaneEnabled(false)
            .enabledRequired(false)
            .tpsEnabled(false)
            .controlIn(controlQueue)
            .controlRoutes(controlRoutes.toArray(String[]::new))
            .data("swarmCount", store.count())));
  }

  public void publishFull() {
    ControlPlaneEmitter.StatusContext context = ControlPlaneEmitter.StatusContext.of(builder -> {
      builder.workPlaneEnabled(false)
          .enabledRequired(false)
          .filesystemEnabled(true)
          .tpsEnabled(false)
          .controlIn(controlQueue)
          .controlRoutes(controlRoutes.toArray(String[]::new))
          .data("swarmCount", store.count())
          .data("startedAt", startedAt);
      var adapterType = lifecycle.currentComputeAdapterType();
      if (adapterType != null) {
        builder.data("computeAdapter", adapterType.name());
      }
    });
    emitter.emitStatusSnapshot(context);
  }

  private static List<String> resolveControlRoutes(
      ControlPlaneRouteCatalog catalog, String instanceId) {
    List<String> routes = new ArrayList<>();
    collectRoutes(routes, catalog.lifecycleEvents(), instanceId);
    collectRoutes(routes, catalog.otherEvents(), instanceId);
    return List.copyOf(routes);
  }

  private static void collectRoutes(List<String> target, Set<String> templates, String instanceId) {
    for (String template : templates) {
      target.add(template.replace(ControlPlaneRouteCatalog.INSTANCE_TOKEN, instanceId));
    }
  }

  private static String requireText(String field, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value.trim();
  }
}
