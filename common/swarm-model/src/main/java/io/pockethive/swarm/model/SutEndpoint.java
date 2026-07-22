package io.pockethive.swarm.model;

import jakarta.validation.constraints.NotBlank;

/**
 * Endpoint definition inside a System Under Test (SUT) environment.
 * <p>
 * The initial version is HTTP‑centric and models endpoints via a
 * protocol kind and a base URL. Additional protocol‑specific fields
 * can be added in future iterations.
 */
public record SutEndpoint(@NotBlank String kind,
                          @NotBlank String baseUrl,
                          String upstreamBaseUrl) {

    public SutEndpoint {
        kind = requireText(kind, "kind");
        baseUrl = requireText(baseUrl, "baseUrl");
        upstreamBaseUrl = trimOptional(upstreamBaseUrl, "upstreamBaseUrl");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("SUT endpoint " + field + " must not be blank");
        }
        return value.trim();
    }

    private static String trimOptional(String value, String field) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException("SUT endpoint " + field + " must not be blank when provided");
        }
        return value.trim();
    }
}
