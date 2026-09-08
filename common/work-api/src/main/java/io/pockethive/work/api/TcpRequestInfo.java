package io.pockethive.work.api;

import java.util.Locale;

/**
 * Responsibility: define the TcpRequestInfo contract.
 * Must not: configure transport clients or own adapter lifecycle.
 * Contract: RESP-WORK-TCP-CONTRACT — docs/architecture/runtime-responsibilities.md#resp-work-tcp-contract.
 */
public record TcpRequestInfo(
    String transport,
    String scheme,
    String method,
    String configuredTarget,
    String endpoint
) {
    public TcpRequestInfo {
        transport = TcpResultEnvelope.normalize(transport, "transport");
        scheme = TcpResultEnvelope.normalizeNullable(scheme);
        method = TcpResultEnvelope.normalize(method, "method").toUpperCase(Locale.ROOT);
        configuredTarget = TcpResultEnvelope.normalizeNullable(configuredTarget);
        endpoint = TcpResultEnvelope.normalizeNullable(endpoint);
    }
}
