package io.pockethive.control;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;
import java.util.Map;

/**
 * High-level classification of a control command's intended target.
 */
public enum CommandTarget {

    /** Applies to the entire hive or all managed components. */
    ALL,

    /** Applies to a specific swarm aggregate. */
    SWARM,

    /** Applies to a service role across the swarm. */
    ROLE,

    /** Applies to a single service instance. */
    INSTANCE;

    @JsonValue
    public String json() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static CommandTarget from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return CommandTarget.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    public static CommandTarget infer(String swarmId,
                                      String role,
                                      String instance,
                                      String explicitTarget,
                                      Map<String, Object> args) {
        if (instance != null && !instance.isBlank()) {
            return INSTANCE;
        }
        if (role != null && !role.isBlank()) {
            return ROLE;
        }
        if (swarmId != null && !swarmId.isBlank()) {
            return SWARM;
        }
        String hint = explicitTarget;
        if ((hint == null || hint.isBlank()) && args != null) {
            Object scope = args.get("scope");
            if (scope instanceof String scopeText && !scopeText.isBlank()) {
                hint = scopeText;
            } else {
                Object argTarget = args.get("target");
                if (argTarget instanceof String targetText && !targetText.isBlank()) {
                    hint = targetText;
                }
            }
        }
        if (hint == null || hint.isBlank()) {
            return ALL;
        }
        return switch (hint.toLowerCase(Locale.ROOT)) {
            case "swarm" -> SWARM;
            case "role" -> ROLE;
            case "controller", "instance" -> INSTANCE;
            case "all" -> ALL;
            default -> ALL;
        };
    }
}

