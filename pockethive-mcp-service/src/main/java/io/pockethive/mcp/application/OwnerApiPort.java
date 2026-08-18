package io.pockethive.mcp.application;

public interface OwnerApiPort {
    Object get(String path);

    Object post(String path, Object body);

    Object delete(String path);
}
