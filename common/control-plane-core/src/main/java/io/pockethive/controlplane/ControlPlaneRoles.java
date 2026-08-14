package io.pockethive.controlplane;

import io.pockethive.swarm.model.BeeRoles;

/** Canonical role names used by control-plane identities and routing scopes. */
public final class ControlPlaneRoles {

  public static final String ORCHESTRATOR = "orchestrator";
  public static final String SWARM_CONTROLLER = BeeRoles.SWARM_CONTROLLER;

  private ControlPlaneRoles() {
  }
}
