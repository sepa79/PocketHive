package io.pockethive.controlplane.topology;

import java.util.Objects;
import java.util.Optional;

public final class ScenarioManagerTopologyDescriptor implements ControlPlaneTopologyDescriptor {

    private static final String ROLE = "scenario-manager";

    @Override
    public String role() {
        return ROLE;
    }

    @Override
    public Optional<ControlQueueDescriptor> controlQueue(String instanceId) {
        Objects.requireNonNull(instanceId, "instanceId");
        return Optional.empty();
    }

    @Override
    public ControlPlaneRouteCatalog routes() {
        return ControlPlaneRouteCatalog.empty();
    }
}
