package io.pockethive.scenarios;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Describes a single endpoint inside a System Under Test (SUT) environment.
 * <p>
 * The initial version is HTTP-centric and keeps only the data needed by
 * HTTP-based workers. Additional protocol-specific fields can be added later
 * as needed.
 */
public final class SutEndpoint {

    private final String id;
    private final String kind;
    private final String baseUrl;

    @JsonCreator
    public SutEndpoint(@JsonProperty("id") String id,
                       @JsonProperty("kind") String kind,
                       @JsonProperty("baseUrl") String baseUrl) {
        this.id = id;
        this.kind = kind;
        this.baseUrl = baseUrl;
    }

    /**
     * Optional explicit identifier. When endpoints are stored inside an
     * environment map keyed by endpoint id, this may be {@code null}.
     */
    public String getId() {
        return id;
    }

    /**
     * Short protocol / shape descriptor, e.g. {@code HTTP}, {@code ISO8583}.
     */
    public String getKind() {
        return kind;
    }

    /**
     * Base URL for HTTP endpoints (e.g. http://host:port) or TCP endpoints (e.g. host:port).
     */
    public String getBaseUrl() {
        return baseUrl;
    }
}

