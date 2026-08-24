package io.pockethive.mcp.application;

import java.net.URI;
import java.time.Instant;

public record EnvironmentServiceHealth(
    String id,
    String name,
    URI endpoint,
    EnvironmentServiceStatus status,
    Instant observedAt
) {
}
