package io.pockethive.swarm.model;

public final class BeeRoles {
    private BeeRoles() {
    }

    public static final String GENERATOR = "generator";
    public static final String MODERATOR = "moderator";
    public static final String PROCESSOR = "processor";
    public static final String POSTPROCESSOR = "postprocessor";
    public static final String REQUEST_BUILDER = "request-builder";
    public static final String HTTP_SEQUENCE = "http-sequence";
    public static final String SWARM_CONTROLLER = "swarm-controller";
    public static final String CLEARING_EXPORT = "clearing-export";
    public static final String TRIGGER = "trigger";
}
