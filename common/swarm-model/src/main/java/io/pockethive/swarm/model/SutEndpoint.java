package io.pockethive.swarm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;

/**
 * Endpoint definition inside a System Under Test (SUT) environment.
 * <p>
 * The initial version is HTTP‑centric and models endpoints via a
 * protocol kind and a base URL. Additional protocol‑specific fields
 * can be added in future iterations.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SutEndpoint(@NotBlank String id,
                          @NotBlank String kind,
                          @NotBlank String baseUrl) {
}

