package io.pockethive.worker.sdk.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuthConfigTest {

    @Test
    void normalizesTypeToKebabCase() {
        Map<String, Object> section = new HashMap<>();
        section.put("type", "BEARER_TOKEN");
        section.put("token", "abc123");
        section.put("tokenKey", "api:auth");

        AuthConfig config = AuthConfig.fromTemplate(section, "service", "call");

        assertThat(config.type()).isEqualTo("bearer-token");
    }
}
