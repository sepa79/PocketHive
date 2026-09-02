package io.pockethive.swarmcontroller.runtime;

import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.SwarmPlan;
import io.pockethive.swarm.model.Topology;
import io.pockethive.swarm.model.TopologyEdge;
import io.pockethive.swarm.model.TopologyEndpoint;
import io.pockethive.swarm.model.TopologySelector;
import io.pockethive.swarm.model.Work;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Responsibility: Project the scenario topology and materialized worker identities into status work bindings.
 * Must not: Mutate runtime state, declare topology, publish status, or infer missing worker identities.
 * Contract: Preserve scenario edge order and map each valid endpoint through the canonical traffic queue settings.
 */
final class SwarmWorkBindingsProjector {

  private static final String EXCHANGE = "exchange";
  private static final String EDGES = "edges";
  private static final String EDGE_ID = "edgeId";
  private static final String FROM = "from";
  private static final String TO = "to";
  private static final String SELECTOR = "selector";
  private static final String ROLE = "role";
  private static final String INSTANCE = "instance";
  private static final String PORT = "port";
  private static final String ROUTING_KEY = "routingKey";
  private static final String QUEUE = "queue";
  private static final String POLICY = "policy";
  private static final String EXPRESSION = "expr";

  private final SwarmControllerProperties.Traffic traffic;

  SwarmWorkBindingsProjector(SwarmControllerProperties.Traffic traffic) {
    this.traffic = Objects.requireNonNull(traffic, "traffic");
  }

  Map<String, Object> project(SwarmPlan plan, Map<String, List<String>> instancesByRole) {
    Map<String, Object> work = new LinkedHashMap<>();
    work.put(EXCHANGE, traffic.hiveExchange());
    List<Map<String, Object>> edgesPayload = new java.util.ArrayList<>();
    work.put(EDGES, edgesPayload);

    if (plan == null) {
      return Map.copyOf(work);
    }
    Topology topology = plan.topology();
    if (topology == null || topology.edges().isEmpty()) {
      return Map.copyOf(work);
    }

    Map<String, Bee> beesByRole = beesByRole(plan.bees());
    Map<String, String> instanceByRole = mapInstancesByRole(beesByRole.keySet(), instancesByRole);
    for (TopologyEdge edge : topology.edges()) {
      if (edge == null) {
        continue;
      }
      Bee fromBee = beesByRole.get(edge.from().role());
      Bee toBee = beesByRole.get(edge.to().role());
      if (fromBee == null || toBee == null) {
        continue;
      }
      Map<String, Object> edgePayload = new LinkedHashMap<>();
      edgePayload.put(EDGE_ID, edge.id());
      edgePayload.put(FROM, endpointPayload(edge.from(), fromBee, instanceByRole, true));
      edgePayload.put(TO, endpointPayload(edge.to(), toBee, instanceByRole, false));
      TopologySelector selector = edge.selector();
      if (selector != null) {
        Map<String, Object> selectorPayload = new LinkedHashMap<>();
        putIfText(selectorPayload, POLICY, selector.policy());
        putIfText(selectorPayload, EXPRESSION, selector.expr());
        if (!selectorPayload.isEmpty()) {
          edgePayload.put(SELECTOR, selectorPayload);
        }
      }
      edgesPayload.add(edgePayload);
    }
    return Map.copyOf(work);
  }

  private static Map<String, Bee> beesByRole(List<Bee> bees) {
    if (bees == null || bees.isEmpty()) {
      return Map.of();
    }
    Map<String, Bee> mapping = new LinkedHashMap<>();
    for (Bee bee : bees) {
      if (bee == null || !hasText(bee.role())) {
        continue;
      }
      Bee previous = mapping.putIfAbsent(bee.role(), bee);
      if (previous != null) {
        throw new IllegalStateException("duplicate scenario bee role: " + bee.role());
      }
    }
    return mapping.isEmpty() ? Map.of() : Map.copyOf(mapping);
  }

  private static Map<String, String> mapInstancesByRole(
      Set<String> roles,
      Map<String, List<String>> instancesByRole) {
    if (roles == null || roles.isEmpty() || instancesByRole == null || instancesByRole.isEmpty()) {
      return Map.of();
    }
    Map<String, String> mapping = new HashMap<>();
    for (String role : roles) {
      if (!hasText(role)) {
        continue;
      }
      List<String> instances = instancesByRole.get(role);
      if (instances == null || instances.isEmpty()) {
        continue;
      }
      if (instances.size() > 1) {
        throw new IllegalStateException("duplicate runtime worker role: " + role);
      }
      mapping.put(role, instances.getFirst());
    }
    return mapping.isEmpty() ? Map.of() : Map.copyOf(mapping);
  }

  private Map<String, Object> endpointPayload(
      TopologyEndpoint endpoint,
      Bee bee,
      Map<String, String> instanceByRole,
      boolean source) {
    Map<String, Object> payload = new LinkedHashMap<>();
    if (endpoint == null || bee == null) {
      return payload;
    }
    putIfText(payload, ROLE, bee.role());
    if (instanceByRole != null && hasText(endpoint.role())) {
      putIfText(payload, INSTANCE, instanceByRole.get(endpoint.role()));
    }
    putIfText(payload, PORT, endpoint.port());
    Work work = bee.work();
    if (work != null) {
      Map<String, String> ports = source ? work.out() : work.in();
      if (ports != null && !ports.isEmpty()) {
        String suffix = ports.get(endpoint.port());
        if (hasText(suffix)) {
          payload.put(source ? ROUTING_KEY : QUEUE, traffic.queueName(suffix));
        }
      }
    }
    return payload;
  }

  private static void putIfText(Map<String, Object> target, String key, String value) {
    if (hasText(value)) {
      target.put(key, value);
    }
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
