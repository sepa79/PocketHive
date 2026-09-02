package io.pockethive.swarmcontroller.runtime;

import io.pockethive.manager.ports.ComputeAdapter;
import io.pockethive.manager.runtime.WorkerSpec;
import io.pockethive.swarm.model.lifecycle.RemoveResource;
import io.pockethive.swarm.model.lifecycle.RemoveResourceType;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import io.pockethive.swarmcontroller.infra.amqp.SwarmQueueMetrics;
import io.pockethive.swarmcontroller.infra.amqp.SwarmWorkTopologyManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.TopicExchange;

/**
 * Responsibility: Apply and remove the compute and AMQP resources selected by the runtime state machine.
 * Must not: Parse plans, decide lifecycle transitions, or own worker/readiness domain state.
 * Contract: Execute explicit adapter operations and report every targeted worker, queue, and exchange resource.
 */
public final class SwarmRuntimeInfrastructure {

  private static final Logger log = LoggerFactory.getLogger(SwarmRuntimeInfrastructure.class);

  private final AmqpAdmin amqp;
  private final SwarmControllerProperties properties;
  private final SwarmWorkTopologyManager topology;
  private final ComputeAdapter computeAdapter;
  private final SwarmQueueMetrics queueMetrics;
  private final String swarmId;
  private final Set<String> declaredQueueSuffixes = new HashSet<>();

  public SwarmRuntimeInfrastructure(
      AmqpAdmin amqp,
      SwarmControllerProperties properties,
      SwarmWorkTopologyManager topology,
      ComputeAdapter computeAdapter,
      SwarmQueueMetrics queueMetrics) {
    this.amqp = Objects.requireNonNull(amqp, "amqp");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.topology = Objects.requireNonNull(topology, "topology");
    this.computeAdapter = Objects.requireNonNull(computeAdapter, "computeAdapter");
    this.queueMetrics = Objects.requireNonNull(queueMetrics, "queueMetrics");
    this.swarmId = properties.getSwarmId();
  }

  public void declareWorkTopology(Set<String> queueSuffixes) {
    Objects.requireNonNull(queueSuffixes, "queueSuffixes");
    TopicExchange workExchange = topology.declareWorkExchange();
    topology.declareWorkQueues(workExchange, queueSuffixes, declaredQueueSuffixes);
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
        .map(workerId -> new RemoveResource(RemoveResourceType.WORKER_RUNTIME, workerId))
        .forEach(removed::add);
    for (Map.Entry<String, List<String>> entry : instancesByRole.entrySet()) {
      String workerRole = entry.getKey();
      for (String workerInstanceId : entry.getValue()) {
        String controlQueue = properties.controlQueueName(workerRole, workerInstanceId);
        log.info("deleting control queue {}", controlQueue);
        amqp.deleteQueue(controlQueue);
        removed.add(new RemoveResource(RemoveResourceType.RABBIT_QUEUE, controlQueue));
      }
    }
    return List.copyOf(removed);
  }

  public List<RemoveResource> removeWorkTopology(Set<String> queueSuffixes) {
    Objects.requireNonNull(queueSuffixes, "queueSuffixes");
    List<RemoveResource> removed = new ArrayList<>();
    topology.deleteWorkQueues(queueSuffixes, queueMetrics::unregister);
    queueSuffixes.stream()
        .map(properties::queueName)
        .map(queue -> new RemoveResource(RemoveResourceType.RABBIT_QUEUE, queue))
        .forEach(removed::add);
    topology.deleteWorkExchange();
    removed.add(new RemoveResource(RemoveResourceType.RABBIT_EXCHANGE, properties.hiveExchange()));
    declaredQueueSuffixes.clear();
    return List.copyOf(removed);
  }

  public Set<String> declaredQueueSuffixes() {
    return Set.copyOf(declaredQueueSuffixes);
  }
}
