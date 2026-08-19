package io.pockethive.mcp.application;

public record SkillDescriptor(
    String id,
    String name,
    String description,
    String version,
    String contentDigest,
    String resourceUri,
    String markdown
) {
}
