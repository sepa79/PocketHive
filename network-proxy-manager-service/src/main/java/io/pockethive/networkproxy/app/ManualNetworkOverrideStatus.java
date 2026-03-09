package io.pockethive.networkproxy.app;

import java.time.Instant;

public record ManualNetworkOverrideStatus(boolean enabled,
                                          Integer latencyMs,
                                          Integer jitterMs,
                                          Integer bandwidthKbps,
                                          Integer slowCloseDelayMs,
                                          Integer limitDataBytes,
                                          Integer timeoutMs,
                                          String requestedBy,
                                          String reason,
                                          Instant appliedAt) {
}
