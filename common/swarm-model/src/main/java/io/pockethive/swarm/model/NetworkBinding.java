package io.pockethive.swarm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NetworkBinding(@NotBlank String swarmId,
                             @NotBlank String sutId,
                             NetworkMode networkMode,
                             String networkProfileId,
                             NetworkMode effectiveMode,
                             @NotBlank String requestedBy,
                             Instant appliedAt,
                             @Valid List<ResolvedSutEndpoint> affectedEndpoints) {

    public NetworkBinding {
        swarmId = requireText(swarmId, "swarmId");
        sutId = requireText(sutId, "sutId");
        networkMode = NetworkMode.directIfNull(networkMode);
        effectiveMode = NetworkMode.directIfNull(effectiveMode);
        networkProfileId = trimToNull(networkProfileId);
        requestedBy = requireText(requestedBy, "requestedBy");
        if (appliedAt == null) {
            throw new IllegalArgumentException("appliedAt must not be null");
        }
        if (networkMode == NetworkMode.DIRECT && networkProfileId != null) {
            throw new IllegalArgumentException("networkProfileId requires networkMode=PROXIED");
        }
        if (networkMode == NetworkMode.PROXIED && networkProfileId == null) {
            throw new IllegalArgumentException("networkProfileId must be provided when networkMode=PROXIED");
        }
        affectedEndpoints = affectedEndpoints == null || affectedEndpoints.isEmpty()
            ? List.of()
            : List.copyOf(affectedEndpoints);
    }

    public List<ResolvedSutEndpoint> affectedEndpoints() {
        return Collections.unmodifiableList(affectedEndpoints);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
