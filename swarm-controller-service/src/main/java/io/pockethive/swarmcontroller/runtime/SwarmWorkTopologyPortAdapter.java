package io.pockethive.swarmcontroller.runtime;

import io.pockethive.manager.ports.WorkTopologyPort;
import io.pockethive.swarmcontroller.infra.amqp.SwarmWorkTopologyManager;
import java.util.Objects;
import java.util.Set;
import org.springframework.amqp.core.TopicExchange;

/**
 * Adapter that bridges {@link WorkTopologyPort} to {@link SwarmWorkTopologyManager}.
 */
public final class SwarmWorkTopologyPortAdapter implements WorkTopologyPort {

  private final SwarmWorkTopologyManager topology;

  public SwarmWorkTopologyPortAdapter(SwarmWorkTopologyManager topology) {
    this.topology = Objects.requireNonNull(topology, "topology");
  }

  @Override
  public String declareWorkExchange() {
    TopicExchange exchange = topology.declareWorkExchange();
    return exchange.getName();
  }

  @Override
  public void declareWorkQueues(String workExchange, Set<String> suffixes, Set<String> declaredSuffixes) {
    Objects.requireNonNull(workExchange, "workExchange");
    TopicExchange exchange = new TopicExchange(workExchange, true, false);
    topology.declareWorkQueues(exchange, suffixes, declaredSuffixes);
  }

  @Override
  public void deleteWorkQueues(Set<String> suffixes) {
    topology.deleteWorkQueues(suffixes, null);
  }

  @Override
  public void deleteWorkExchange() {
    topology.deleteWorkExchange();
  }
}

