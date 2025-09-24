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
    String target,
    Map<String, Object> args
) {

    public ControlSignal {
        if (target != null) {
            target = target.trim();
            if (target.isEmpty()) {
                target = null;
            }
        }
        if (target == null && args != null && !args.isEmpty()) {
            Object argTarget = args.get("target");
            if (argTarget instanceof String targetText && !targetText.isBlank()) {
                target = targetText;
            } else {
                Object scope = args.get("scope");
                if (scope instanceof String scopeText && !scopeText.isBlank()) {
                    target = scopeText;
                }
            }
        }
        if (args != null) {
            args = Collections.unmodifiableMap(new LinkedHashMap<>(args));
        }
        if (commandTarget == null) {
            commandTarget = CommandTarget.infer(swarmId, role, instance, target, args);
        }
    }

    public static ControlSignal forSwarm(String signal, String swarmId, String correlationId, String idempotencyKey) {
        return new ControlSignal(signal, correlationId, idempotencyKey, swarmId, null, null,
            CommandTarget.SWARM, null, null);
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
            CommandTarget.INSTANCE, null, args);
    }

    public static ControlSignal forInstance(String signal,
                                            String swarmId,
                                            String role,
                                            String instance,
                                            String correlationId,
                                            String idempotencyKey,
                                            CommandTarget commandTarget,
                                            String target,
                                            Map<String, Object> args) {
        return new ControlSignal(signal, correlationId, idempotencyKey, swarmId, role, instance, commandTarget, target, args);
    }
}
