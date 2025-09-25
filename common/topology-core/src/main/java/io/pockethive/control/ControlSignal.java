package io.pockethive.control;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Unified control-plane signal envelope shared by orchestrator and controller.
 */
public record ControlSignal(
    String signal,
    String correlationId,
    String idempotencyKey,
    String swarmId,
    String role,
    String instance,
    CommandTarget commandTarget,
    Map<String, Object> args
) {

    public ControlSignal {
        if (args != null) {
            args = Collections.unmodifiableMap(new LinkedHashMap<>(args));
        }
        if (commandTarget == null) {
            commandTarget = CommandTarget.infer(swarmId, role, instance, args);
        }
    }

    public static ControlSignal forSwarm(String signal, String swarmId, String correlationId, String idempotencyKey) {
        return new ControlSignal(signal, correlationId, idempotencyKey, swarmId, null, null,
            CommandTarget.SWARM, null);
    }

    public static ControlSignal forInstance(String signal,
                                            String swarmId,
                                            String role,
                                            String instance,
                                            String correlationId,
                                            String idempotencyKey) {
        return forInstance(signal, swarmId, role, instance, correlationId, idempotencyKey, null);
    }

    public static ControlSignal forInstance(String signal,
                                            String swarmId,
                                            String role,
                                            String instance,
                                            String correlationId,
                                            String idempotencyKey,
                                            Map<String, Object> args) {
        return new ControlSignal(signal, correlationId, idempotencyKey, swarmId, role, instance,
            CommandTarget.INSTANCE, args);
    }

    public static ControlSignal forInstance(String signal,
                                            String swarmId,
                                            String role,
                                            String instance,
                                            String correlationId,
                                            String idempotencyKey,
                                            CommandTarget commandTarget,
                                            Map<String, Object> args) {
        CommandTarget resolved = commandTarget == null ? CommandTarget.INSTANCE : commandTarget;
        return new ControlSignal(signal, correlationId, idempotencyKey, swarmId, role, instance,
            resolved, args);
    }
}
