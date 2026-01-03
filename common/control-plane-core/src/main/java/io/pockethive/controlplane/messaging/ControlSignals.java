package io.pockethive.controlplane.messaging;

import io.pockethive.control.ControlSignal;
import io.pockethive.control.ControlScope;
import io.pockethive.controlplane.ControlPlaneSignals;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Canonical factories for control-plane command signals (kind=signal).
 *
 * <p>All new signal types and their {@code data} shapes must be introduced here and in
 * {@code docs/spec} rather than being emitted ad-hoc by producers.</p>
 */
public final class ControlSignals {

    private ControlSignals() {
    }

    public static ControlSignal swarmCreate(String origin,
                                            ControlScope target,
                                            String correlationId,
                                            String idempotencyKey,
                                            Map<String, Object> data) {
        return signal(ControlPlaneSignals.SWARM_CREATE, origin, target, correlationId, idempotencyKey, data);
    }

    public static ControlSignal swarmControllerReady(String origin,
                                                     ControlScope target,
                                                     String correlationId,
                                                     String idempotencyKey) {
        return signal("swarm-controller", origin, target, correlationId, idempotencyKey, null);
    }

    public static ControlSignal swarmTemplate(String origin,
                                              ControlScope target,
                                              String correlationId,
                                              String idempotencyKey,
                                              Map<String, Object> data) {
        return signal(ControlPlaneSignals.SWARM_TEMPLATE, origin, target, correlationId, idempotencyKey, data);
    }

    public static ControlSignal swarmPlan(String origin,
                                          ControlScope target,
                                          String correlationId,
                                          String idempotencyKey,
                                          Map<String, Object> data) {
        return signal(ControlPlaneSignals.SWARM_PLAN, origin, target, correlationId, idempotencyKey, data);
    }

    public static ControlSignal swarmStart(String origin,
                                           ControlScope target,
                                           String correlationId,
                                           String idempotencyKey) {
        return signal(ControlPlaneSignals.SWARM_START, origin, target, correlationId, idempotencyKey, null);
    }

    public static ControlSignal swarmStop(String origin,
                                          ControlScope target,
                                          String correlationId,
                                          String idempotencyKey) {
        return signal(ControlPlaneSignals.SWARM_STOP, origin, target, correlationId, idempotencyKey, null);
    }

    public static ControlSignal swarmRemove(String origin,
                                            ControlScope target,
                                            String correlationId,
                                            String idempotencyKey) {
        return signal(ControlPlaneSignals.SWARM_REMOVE, origin, target, correlationId, idempotencyKey, null);
    }

    public static ControlSignal statusRequest(String origin,
                                              ControlScope target,
                                              String correlationId,
                                              String idempotencyKey) {
        return signal(ControlPlaneSignals.STATUS_REQUEST, origin, target, correlationId, idempotencyKey, null);
    }

    public static ControlSignal configUpdate(String origin,
                                             ControlScope target,
                                             String correlationId,
                                             String idempotencyKey,
                                             Map<String, Object> patchData) {
        Map<String, Object> args = null;
        if (patchData != null && !patchData.isEmpty()) {
            args = new LinkedHashMap<>(patchData);
        }
        return signal(ControlPlaneSignals.CONFIG_UPDATE, origin, target, correlationId, idempotencyKey, args);
    }

    public static ControlSignal signal(String type,
                                       String origin,
                                       ControlScope target,
                                       String correlationId,
                                       String idempotencyKey,
                                       Map<String, Object> data) {
        Objects.requireNonNull(target, "target");
        return ControlSignal.signal(
            type,
            origin,
            target,
            correlationId,
            idempotencyKey,
            data
        );
    }
}
