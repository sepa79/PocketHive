package io.pockethive.mcp.application;

import java.net.URI;

public record EnvironmentHealthTarget(
    String id,
    String name,
    URI endpointPath,
    String probePath,
    EnvironmentHealthContract contract
) {
}
