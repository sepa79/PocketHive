package io.pockethive.mcp.application;

/**
 * Responsibility: Define the owner-service HTTP application port used through public ingress.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: docs/mcp/README.md.
 */

public interface OwnerApiPort {
    Object get(String path);

    String getText(String path);

    Object post(String path, Object body);

    Object delete(String path);
}
