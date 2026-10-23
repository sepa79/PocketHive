package io.pockethive.templating;

import java.net.URI;
import java.util.Map;

/**
 * Responsibility: carry one rendered fixed-origin portable request.
 * Must not: execute HTTP, retry or persist results.
 * Contract: RESP-PORTABLE-HTTP — docs/architecture/runtime-responsibilities.md#resp-portable-http.
 */
public record PortableHttpRequest(URI uri, String method, String body, Map<String, String> headers) {
    public PortableHttpRequest { headers = Map.copyOf(headers); }
}
