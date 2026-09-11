package io.pockethive.swarmcontroller.infra.amqp;

import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import java.util.Objects;
import io.pockethive.topology.work.WorkResourceNamesPort;
import java.util.Set;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.pockethive.rabbit.api.RabbitResources;
import io.pockethive.rabbit.api.RabbitQueueSpec;
import io.pockethive.rabbit.api.RabbitExchangeSpec;
import io.pockethive.rabbit.api.RabbitBindingSpec;
import java.util.Map;

/**
 * Responsibility: translate the swarm queue requirements into calls to Rabbit resource and naming APIs.
 * Must not: construct resource names, validate worker settings or own lifecycle convergence.
 * Contract: RESP-WORK-RESOURCE-NAMES — docs/architecture/runtime-responsibilities.md#resp-work-resource-names.
 */
public final class SwarmWorkTopologyManager {

  private static final Logger log = LoggerFactory.getLogger(SwarmWorkTopologyManager.class);

  private final WorkResourceNamesPort names;
  private final RabbitResources amqp;
  private final SwarmControllerProperties properties;

  public SwarmWorkTopologyManager(@org.springframework.beans.factory.annotation.Qualifier(io.pockethive.rabbit.api.RabbitResourceBeans.WORK) RabbitResources amqp, SwarmControllerProperties properties, WorkResourceNamesPort names) {
    this.names = Objects.requireNonNull(names, "names");
    this.amqp = Objects.requireNonNull(amqp, "amqp");
    this.properties = Objects.requireNonNull(properties, "properties");
  }

  /**
   * Declare (or ensure existence of) the work exchange for the current swarm.
   *
   * @return the declared exchange name.
   */
  public String declareWorkExchange() {
    String hive = names.exchangeName(properties.getTraffic().hiveExchange());
    amqp.declareExchange(new RabbitExchangeSpec(hive, true, false, Map.of()));
    log.info("declared work exchange {}", names.exchangeName(properties.getTraffic().hiveExchange()));
    return hive;
  }

  /**
   * Declare all work queues for the provided suffixes and bind them to the work exchange.
   * <p>
   * The supplied {@code declaredSuffixes} set tracks which suffixes have already been
   * declared so repeated calls can skip redundant declarations while still healing
   * missing queues.
   */
  public void declareWorkQueues(String workExchange,
                                Set<String> suffixes,
                                Set<String> declaredSuffixes) {
    Objects.requireNonNull(workExchange, "workExchange");
    Objects.requireNonNull(suffixes, "suffixes");
    Objects.requireNonNull(declaredSuffixes, "declaredSuffixes");

    for (String suffix : suffixes) {
      var address = names.address(workExchange, properties.getTraffic().queuePrefix(), suffix);
      String queueName = address.queue();
      boolean queueMissing = amqp.queue(queueName).isEmpty();
      if (queueMissing) {
        declaredSuffixes.remove(suffix);
      }
      RabbitBindingSpec legacyBinding = new RabbitBindingSpec(queueName, address.exchange(), suffix, Map.of());
      amqp.unbind(legacyBinding);

      RabbitQueueSpec queue = new RabbitQueueSpec(queueName, true, false, false, Map.of());
      if (queueMissing || !declaredSuffixes.contains(suffix)) {
        amqp.declareQueue(queue);
        log.info("declared queue {}", queueName);
      }

      amqp.bind(new RabbitBindingSpec(queueName, address.exchange(), address.routingKey(), Map.of()));
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
      String queueName = names.queueName(properties.getTraffic().queuePrefix(), suffix);
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
    amqp.deleteExchange(names.exchangeName(properties.getTraffic().hiveExchange()));
  }
}
