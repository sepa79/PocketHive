package io.pockethive.orchestrator.app;

import io.pockethive.control.ControlScope;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.manager.ManagerControlPlane;
import io.pockethive.controlplane.messaging.ControlSignals;
import io.pockethive.controlplane.messaging.SignalMessage;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.observability.ControlPlaneJson;
import io.pockethive.orchestrator.domain.SwarmStateStore;
import java.util.Objects;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class ControlPlaneStatusRequestPublisher {

    private static final Logger log = LoggerFactory.getLogger(ControlPlaneStatusRequestPublisher.class);

    private final ManagerControlPlane controlPlane;
    private final ControlPlaneIdentity identity;
    private final SwarmStateStore stateStore;

    public ControlPlaneStatusRequestPublisher(ManagerControlPlane controlPlane,
                                              @Qualifier("managerControlPlaneIdentity") ControlPlaneIdentity identity,
                                              SwarmStateStore stateStore) {
        this.controlPlane = Objects.requireNonNull(controlPlane, "controlPlane");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
    }

    public void requestStatusForSwarm(String swarmId, String correlationId, String idempotencyKey) {
        if (swarmId == null || swarmId.isBlank()) {
            throw new IllegalArgumentException("swarmId must not be blank");
        }
        ControlScope target = ControlScope.forSwarm(swarmId);
        Map<String, Object> runtime = stateStore.requireRuntimeFromLatestStatusFull(swarmId);
        publish(target,
            ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, swarmId, ControlScope.ALL, ControlScope.ALL),
            correlationId,
            idempotencyKey,
            runtime);
    }

    public void requestStatusForAllControllers(String correlationId, String idempotencyKey) {
        ControlScope target = new ControlScope(ControlScope.ALL, "swarm-controller", ControlScope.ALL);
        publish(target,
            ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, ControlScope.ALL, "swarm-controller", ControlScope.ALL),
            correlationId,
            idempotencyKey,
            null);
    }

    public void requestStatusForSwarm(String swarmId,
                                      String correlationId,
                                      String idempotencyKey,
                                      Map<String, Object> runtime) {
        if (swarmId == null || swarmId.isBlank()) {
            throw new IllegalArgumentException("swarmId must not be blank");
        }
        ControlScope target = ControlScope.forSwarm(swarmId);
        publish(target,
            ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, swarmId, ControlScope.ALL, ControlScope.ALL),
            correlationId,
            idempotencyKey,
            runtime);
    }

    private void publish(ControlScope target,
                         String routingKey,
                         String correlationId,
                         String idempotencyKey,
                         Map<String, Object> runtime) {
        var signal = ControlSignals.statusRequest(identity.instanceId(), target, correlationId, idempotencyKey, runtime);
        String payload = ControlPlaneJson.write(signal, "status-request signal");
        controlPlane.publishSignal(new SignalMessage(routingKey, payload));
        log.info("[CTRL] SEND status-request rk={} correlationId={}", routingKey, correlationId);
    }

}
