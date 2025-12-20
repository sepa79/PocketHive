package io.pockethive.orchestrator.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlScope;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.manager.ManagerControlPlane;
import io.pockethive.controlplane.messaging.ControlSignals;
import io.pockethive.controlplane.messaging.SignalMessage;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class ControlPlaneStatusRequestPublisher {

    private static final Logger log = LoggerFactory.getLogger(ControlPlaneStatusRequestPublisher.class);

    private final ManagerControlPlane controlPlane;
    private final ObjectMapper mapper;
    private final ControlPlaneIdentity identity;

    public ControlPlaneStatusRequestPublisher(ManagerControlPlane controlPlane,
                                              ObjectMapper mapper,
                                              @Qualifier("managerControlPlaneIdentity") ControlPlaneIdentity identity) {
        this.controlPlane = Objects.requireNonNull(controlPlane, "controlPlane");
        this.mapper = Objects.requireNonNull(mapper, "mapper").findAndRegisterModules();
        this.identity = Objects.requireNonNull(identity, "identity");
    }

    public void requestStatusForSwarm(String swarmId, String correlationId, String idempotencyKey) {
        if (swarmId == null || swarmId.isBlank()) {
            throw new IllegalArgumentException("swarmId must not be blank");
        }
        ControlScope target = ControlScope.forSwarm(swarmId);
        publish(target,
            ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, swarmId, "ALL", "ALL"),
            correlationId,
            idempotencyKey);
    }

    public void requestStatusForAllControllers(String correlationId, String idempotencyKey) {
        ControlScope target = new ControlScope(null, "swarm-controller", null);
        publish(target,
            ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, "ALL", "swarm-controller", "ALL"),
            correlationId,
            idempotencyKey);
    }

    private void publish(ControlScope target, String routingKey, String correlationId, String idempotencyKey) {
        try {
            var signal = ControlSignals.statusRequest(identity.instanceId(), target, correlationId, idempotencyKey);
            String payload = mapper.writeValueAsString(signal);
            controlPlane.publishSignal(new SignalMessage(routingKey, payload));
            log.info("[CTRL] SEND status-request rk={} correlationId={}", routingKey, correlationId);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise status-request signal", e);
        }
    }
}

