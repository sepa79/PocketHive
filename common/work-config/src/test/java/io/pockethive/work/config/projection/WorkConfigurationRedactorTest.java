package io.pockethive.work.config.projection;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkConfigurationRedactorTest {
    @Test
    void redactsPasswordsAcrossStatusAndDiffWrappersWithoutChangingConfiguration() {
        Map<String, Object> connection = new LinkedHashMap<>();
        connection.put("host", "redis");
        connection.put("password", " secret ");
        connection.put("username", null);
        var config = Map.of("outputs", Map.of("redis", connection));
        var source = Map.of("workers", List.of(config), "added", config, "updated", config);

        var redacted = WorkConfigurationRedactor.redact(source);

        var expectedConfig = Map.of("outputs", Map.of("redis", redactedConnection()));
        assertThat(redacted).isEqualTo(Map.of("workers", List.of(expectedConfig),
            "added", expectedConfig, "updated", expectedConfig));
        assertThat(connection).containsEntry("password", " secret ").containsEntry("username", null);
        assertThat(WorkConfigurationRedactor.redact(redacted)).isEqualTo(redacted);
    }

    private static Map<String, Object> redactedConnection() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("host", "redis");
        result.put("password", "[redacted]");
        result.put("username", null);
        return result;
    }
}
