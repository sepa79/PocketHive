package io.pockethive.swarmcontroller.runtime;

import io.pockethive.manager.ports.ComputeAdapter;
import io.pockethive.manager.runtime.WorkerSpec;
import io.pockethive.rabbit.api.RabbitResources;
import io.pockethive.swarm.model.lifecycle.RemoveResource;
import io.pockethive.swarm.model.lifecycle.RemoveResourceType;
import io.pockethive.swarm.model.lifecycle.ResourcePlane;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import io.pockethive.swarmcontroller.infra.amqp.SwarmQueueMetrics;
import io.pockethive.topology.work.ResolvedWorkTopology;
import io.pockethive.topology.work.WorkPlaneResources;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/**
 * Responsibility: Apply and remove the compute and AMQP resources selected by the runtime state machine.
 * Must not: Parse plans, decide lifecycle transitions, or own worker/readiness domain state.
 * Work operations consume the supplied immutable topology and selected resource owner.
 * Contract: RESP-WORK-RESOURCE-NAMES — docs/architecture/runtime-responsibilities.md#resp-work-resource-names.
 * Behavior: Execute explicit adapter operations and report every targeted worker, queue, and exchange resource.
 */
public final class SwarmRuntimeInfrastructure {

  private static final Logger log = LoggerFactory.getLogger(SwarmRuntimeInfrastructure.class);

  private final RabbitResources amqp;
  private final SwarmControllerProperties properties;
  private final WorkPlaneResources workResources;
  private final ComputeAdapter computeAdapter;
  private final SwarmQueueMetrics queueMetrics;
  private final String swarmId;
  private ResolvedWorkTopology attemptedTopology;

  public SwarmRuntimeInfrastructure(
      RabbitResources amqp,
      SwarmControllerProperties properties,
      WorkPlaneResources workResources,
      ComputeAdapter computeAdapter,
      SwarmQueueMetrics queueMetrics) {
    this.amqp = Objects.requireNonNull(amqp, "amqp");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.workResources = Objects.requireNonNull(workResources, "workResources");
    this.computeAdapter = Objects.requireNonNull(computeAdapter, "computeAdapter");
    this.queueMetrics = Objects.requireNonNull(queueMetrics, "queueMetrics");
    this.swarmId = properties.getSwarmId();
  }

  public void declareWorkTopology(ResolvedWorkTopology topology) {
    attemptedTopology = topology;
    workResources.ensure(topology);
  }

  public void provisionWorkers(List<WorkerSpec> workerSpecs) {
    computeAdapter.applyWorkers(swarmId, List.copyOf(Objects.requireNonNull(workerSpecs, "workerSpecs")));
  }

  public List<RemoveResource> removeWorkers(Map<String, List<String>> instancesByRole) {
    Objects.requireNonNull(instancesByRole, "instancesByRole");
    List<RemoveResource> removed = new ArrayList<>();
    computeAdapter.removeWorkers(swarmId);
    instancesByRole.values().stream()
        .flatMap(List::stream)
        .map(workerId -> new RemoveResource(RemoveResourceType.WORKER_RUNTIME, workerId, ResourcePlane.NONE))
        .forEach(removed::add);
    for (Map.Entry<String, List<String>> entry : instancesByRole.entrySet()) {
      String workerRole = entry.getKey();
      for (String workerInstanceId : entry.getValue()) {
        String controlQueue = properties.controlQueueName(workerRole, workerInstanceId);
        log.info("deleting control queue {}", controlQueue);
        amqp.deleteQueue(controlQueue);
        removed.add(new RemoveResource(RemoveResourceType.RABBIT_QUEUE, controlQueue, ResourcePlane.CONTROL));
      }
    }
    return List.copyOf(removed);
  }

  public List<RemoveResource> removeWorkTopology(ResolvedWorkTopology topology) {
    List<RemoveResource> removed = new ArrayList<>();
    for (var resource : topology.resources().reversed()) {
      var target = workResources.removalTarget(resource);
      workResources.remove(resource);
      removed.add(target);
      topology.channels().values().stream().filter(channel -> channel.resource().equals(resource))
          .forEach(channel -> queueMetrics.unregister(channel.inputAddress()));
    }
    attemptedTopology = topology.retainChannels(Set.of());
    return List.copyOf(removed);
  }

  public ResolvedWorkTopology declaredWorkTopology(ResolvedWorkTopology emptyTopology) {
    return attemptedTopology == null ? emptyTopology
        : attemptedTopology.retainChannels(workResources.appliedResources());
  }
}
