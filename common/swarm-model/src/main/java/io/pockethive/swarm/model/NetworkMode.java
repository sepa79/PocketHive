package io.pockethive.swarm.model;

public enum NetworkMode {
    DIRECT,
    PROXIED;

    public static NetworkMode directIfNull(NetworkMode value) {
        return value == null ? DIRECT : value;
    }
}
