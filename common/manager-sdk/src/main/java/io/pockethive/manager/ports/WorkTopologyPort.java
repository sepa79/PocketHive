package io.pockethive.manager.ports;

import java.util.Set;

/**
 * Port responsible for declaring and tearing down the work exchange and queues
 * for a managed swarm/topology.
 */
public interface WorkTopologyPort {

  /**
   * Declare (or ensure existence of) the work exchange.
   *
   * @return the resolved exchange name.
   */
  String declareWorkExchange();

  /**
   * Declare all work queues for the provided suffixes and bind them to the work exchange.
   *
   * @param workExchange   the work exchange name
   * @param suffixes       plan-derived queue suffixes
   * @param declaredSuffixes mutable set tracking already declared suffixes
   */
  void declareWorkQueues(String workExchange, Set<String> suffixes, Set<String> declaredSuffixes);

  /**
   * Delete all work queues for the provided suffixes.
   *
   * @param suffixes queue suffixes derived from the plan
   */
  void deleteWorkQueues(Set<String> suffixes);

  /**
   * Delete the work exchange.
   */
  void deleteWorkExchange();
}

