package io.pockethive.networkproxy.app;

public record ManualNetworkOverrideRequest(boolean enabled,
                                           Integer latencyMs,
                                           Integer jitterMs,
                                           Integer bandwidthKbps,
                                           Integer timeoutMs,
                                           String requestedBy,
                                           String reason) {
}
