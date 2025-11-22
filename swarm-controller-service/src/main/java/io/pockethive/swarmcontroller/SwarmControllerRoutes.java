package io.pockethive.swarmcontroller;

import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.routing.ControlPlaneRouting;

/**
 * Shared helpers for control-plane routing keys used by the swarm controller.
 */
public final class SwarmControllerRoutes {

  private static final String ALL = "ALL";

  private SwarmControllerRoutes() {
  }

  /**
   * Resolve the canonical set of control routes the swarm controller advertises
   * in its status payloads.
   */
  public static String[] controllerControlRoutes(String swarmId, String role, String instanceId) {
    String swarm = swarmId;
    String controllerRole = role;
    String instance = instanceId;
    return new String[] {
        ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, ALL, controllerRole, ALL),
        ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, swarm, controllerRole, ALL),
        ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, swarm, controllerRole, instance),
        ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, swarm, ALL, ALL),
        ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, ALL, controllerRole, ALL),
        ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, swarm, controllerRole, ALL),
        ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, swarm, controllerRole, instance),
        ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_TEMPLATE, swarm, controllerRole, ALL),
        ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_START, swarm, controllerRole, ALL),
        ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_STOP, swarm, controllerRole, ALL),
        ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_REMOVE, swarm, controllerRole, ALL)
    };
  }
}
