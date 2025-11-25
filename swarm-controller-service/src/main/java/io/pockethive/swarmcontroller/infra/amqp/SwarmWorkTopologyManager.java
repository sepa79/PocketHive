package io.pockethive.swarmcontroller.infra.amqp;

import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;

/**
 * Helper responsible for declaring and tearing down the swarm's work topology
 * (hive exchange + work queues) based on plan-derived queue suffixes.
 * <p>
 * This class is deliberately small and reusable so other swarm-controller
 * implementations or tools can share the same topology wiring logic.
 */
public final class SwarmWorkTopologyManager {

  private static final Logger log = LoggerFactory.getLogger(SwarmWorkTopologyManager.class);

  private final AmqpAdmin amqp;
  private final SwarmControllerProperties properties;

  public SwarmWorkTopologyManager(AmqpAdmin amqp, SwarmControllerProperties properties) {
    this.amqp = Objects.requireNonNull(amqp, "amqp");
    this.properties = Objects.requireNonNull(properties, "properties");
  }

  /**
   * Declare (or ensure existence of) the work exchange for the current swarm.
   *
   * @return the declared {@link TopicExchange}.
   */
  public TopicExchange declareWorkExchange() {
    TopicExchange hive = new TopicExchange(properties.hiveExchange(), true, false);
    amqp.declareExchange(hive);
    log.info("declared work exchange {}", properties.hiveExchange());
    return hive;
  }

  /**
   * Declare all work queues for the provided suffixes and bind them to the work exchange.
   * <p>
   * The supplied {@code declaredSuffixes} set tracks which suffixes have already been
   * declared so repeated calls can skip redundant declarations while still healing
   * missing queues.
   */
  public void declareWorkQueues(TopicExchange workExchange,
                                Set<String> suffixes,
                                Set<String> declaredSuffixes) {
    Objects.requireNonNull(workExchange, "workExchange");
    Objects.requireNonNull(suffixes, "suffixes");
    Objects.requireNonNull(declaredSuffixes, "declaredSuffixes");

    for (String suffix : suffixes) {
      String queueName = properties.queueName(suffix);
      boolean queueMissing = amqp.getQueueProperties(queueName) == null;
      if (queueMissing) {
        declaredSuffixes.remove(suffix);
      }
      Binding legacyBinding = new Binding(queueName, Binding.DestinationType.QUEUE,
          workExchange.getName(), suffix, null);
      amqp.removeBinding(legacyBinding);

      Queue queue = QueueBuilder.durable(queueName).build();
      if (queueMissing || !declaredSuffixes.contains(suffix)) {
        amqp.declareQueue(queue);
        log.info("declared queue {}", queueName);
      }

      Binding desiredBinding = BindingBuilder.bind(queue).to(workExchange).with(queueName);
      amqp.declareBinding(desiredBinding);
      declaredSuffixes.add(suffix);
    }
  }

  /**
   * Delete all work queues for the provided suffixes.
   *
   * @param suffixes queue suffixes derived from the swarm plan
   * @param onQueueDeleted optional callback invoked with each resolved queue name
   */
  public void deleteWorkQueues(Set<String> suffixes, Consumer<String> onQueueDeleted) {
    Objects.requireNonNull(suffixes, "suffixes");
    for (String suffix : suffixes) {
      String queueName = properties.queueName(suffix);
      log.info("deleting queue {}", queueName);
      amqp.deleteQueue(queueName);
      if (onQueueDeleted != null) {
        onQueueDeleted.accept(queueName);
      }
    }
  }

  /**
   * Delete the work exchange for the current swarm.
   */
  public void deleteWorkExchange() {
    amqp.deleteExchange(properties.hiveExchange());
  }
}
