package io.pockethive.control;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Unified control-plane signal envelope shared by orchestrator and controller.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ControlSignal(
    String signal,
    String correlationId,
    String idempotencyKey,
    String swarmId,
    String role,
    String instance,
    String origin,
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
        if (origin != null) {
            origin = origin.trim();
            if (origin.isEmpty()) {
                origin = null;
            }
        }
    }

    public static ControlSignal forSwarm(String signal, String swarmId, String correlationId, String idempotencyKey) {
        return forSwarm(signal, swarmId, correlationId, idempotencyKey, null);
    }

    public static ControlSignal forSwarm(String signal,
                                         String swarmId,
                                         String correlationId,
                                         String idempotencyKey,
                                         String origin) {
        return new ControlSignal(signal, correlationId, idempotencyKey, swarmId, null, null,
            origin, CommandTarget.SWARM, null);
    }

    public static ControlSignal forInstance(String signal,
                                            String swarmId,
                                            String role,
                                            String instance,
                                            String correlationId,
                                            String idempotencyKey) {
        return forInstance(signal, swarmId, role, instance, correlationId, idempotencyKey, null, null);
    }

    public static ControlSignal forInstance(String signal,
                                            String swarmId,
                                            String role,
                                            String instance,
                                            String correlationId,
                                            String idempotencyKey,
                                            Map<String, Object> args) {
        return forInstance(signal, swarmId, role, instance, correlationId, idempotencyKey, null,
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
        return forInstance(signal, swarmId, role, instance, correlationId, idempotencyKey, null,
            commandTarget, args);
    }

    public static ControlSignal forInstance(String signal,
                                            String swarmId,
                                            String role,
                                            String instance,
                                            String correlationId,
                                            String idempotencyKey,
                                            String origin,
                                            CommandTarget commandTarget,
                                            Map<String, Object> args) {
        CommandTarget resolved = commandTarget == null ? CommandTarget.INSTANCE : commandTarget;
        return new ControlSignal(signal, correlationId, idempotencyKey, swarmId, role, instance,
            origin, resolved, args);
    }

    public ControlSignal withOrigin(String origin) {
        return new ControlSignal(signal, correlationId, idempotencyKey, swarmId, role, instance,
            origin, commandTarget, args);
    }
}
