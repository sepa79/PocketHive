package io.pockethive.worker.sdk.auth;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuthProfileTemplateValuesTest {
    @Test
    void preservesNestedTraversalAndOpaqueSecretResults() {
        Object profile = Map.of("fields", List.of("plain", Map.of("env", "ENV"), Map.of("file", "path"),
            Map.of("env", "text", "other", "value")), "number", 12);
        assertThat(AuthProfileTemplateValues.sources(profile)).containsExactlyInAnyOrder("plain", "text", "value");
        Object mapped = AuthProfileTemplateValues.map(profile, value -> "render:" + value,
            value -> "{{ vars.secret }}", value -> Map.of("env", "not-traversed"));
        assertThat((Map<?, ?>) mapped).isEqualTo(Map.of("fields", List.of("render:plain", "{{ vars.secret }}",
            Map.of("env", "not-traversed"), Map.of("env", "render:text", "other", "render:value")), "number", 12));
    }
}
