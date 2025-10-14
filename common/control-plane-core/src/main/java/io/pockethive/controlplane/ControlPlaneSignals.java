package io.pockethive.controlplane;

/**
 * Canonical control-plane signal names.
 */
public final class ControlPlaneSignals {
    private ControlPlaneSignals() {
    }

    public static final String CONFIG_UPDATE = "config-update";

    public static final String STATUS_REQUEST = "status-request";

    public static final String SWARM_CREATE = "swarm-create";

    public static final String SWARM_TEMPLATE = "swarm-template";

    public static final String SWARM_START = "swarm-start";

    public static final String SWARM_STOP = "swarm-stop";

    public static final String SWARM_REMOVE = "swarm-remove";
}
