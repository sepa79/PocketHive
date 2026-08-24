package io.pockethive.mcp.application;

@FunctionalInterface
public interface EnvironmentHealthProbePort {
    boolean healthy(EnvironmentHealthTarget target);
}
