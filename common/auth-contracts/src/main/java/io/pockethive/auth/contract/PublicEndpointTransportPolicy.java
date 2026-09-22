package io.pockethive.auth.contract;

import java.net.URI;

/**
 * Responsibility: Validate transport of explicitly configured public authentication and MCP endpoints.
 * Must not: Resolve addresses, infer URLs, change protocols, or validate client callback registration.
 * Contract: RESP-PUBLIC-ENDPOINT-TRANSPORT — docs/architecture/runtime-responsibilities.md.
 */
public final class PublicEndpointTransportPolicy {
    private PublicEndpointTransportPolicy() { }

    public static boolean allows(URI uri, boolean allowRemoteHttp) {
        if (uri == null || uri.getHost() == null || uri.getUserInfo() != null
            || uri.getQuery() != null || uri.getFragment() != null) {
            return false;
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return true;
        }
        return "http".equalsIgnoreCase(uri.getScheme())
            && (allowRemoteHttp || isLoopback(uri.getHost()));
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
            || "[::1]".equals(host);
    }
}
