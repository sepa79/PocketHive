package io.pockethive.swarm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Collections;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NetworkProfile(@NotBlank String id,
                             @NotBlank String name,
                             @Valid List<NetworkFault> faults,
                             List<String> targets) {

    public NetworkProfile {
        id = requireText(id, "id");
        name = requireText(name, "name");
        faults = faults == null || faults.isEmpty()
            ? List.of()
            : List.copyOf(faults);
        targets = targets == null || targets.isEmpty()
            ? List.of()
            : targets.stream()
                .map(target -> requireText(target, "targets[]"))
                .toList();
    }

    public List<NetworkFault> faults() {
        return Collections.unmodifiableList(faults);
    }

    public List<String> targets() {
        return Collections.unmodifiableList(targets);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
