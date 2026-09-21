package io.pockethive.redis.config;

import io.pockethive.work.config.WorkConfigurationMode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedisOutputSettingsParserTest {
    private final RedisConfigurationParser parser = new RedisConfigurationParser();

    @Test
    void createsCompleteResolvedOutputSettings() {
        var result = parser.validateRedisOutputSettings(settings(), "outputs.redis", WorkConfigurationMode.RESOLVED);

        assertThat(result.problems()).isEmpty();
        assertThat(result.deferredPaths()).isEmpty();
        assertThat(result.settings()).isNotNull();
        assertThat(result.settings().connection().host()).isEqualTo("redis");
        assertThat(result.settings().writeSettings().maxLen()).isEqualTo(-1);
        assertThat(result.settings().defaultList()).isEqualTo("ph:out");
    }

    @Test
    void rejectsUnknownOutputField() {
        var values = new java.util.LinkedHashMap<>(settings());
        values.put("unknown", true);

        var result = parser.validateRedisOutputSettings(values, "outputs.redis", WorkConfigurationMode.RESOLVED);

        assertThat(result.settings()).isNull();
        assertThat(result.problems()).extracting(problem -> problem.path()).contains("outputs.redis.unknown");
    }

    @Test
    void aggregatesComponentProblemsWithoutPartialSettings() {
        var values = new java.util.LinkedHashMap<>(settings());
        values.put("port", 0);
        values.put("maxLen", -2);

        var result = parser.validateRedisOutputSettings(values, "outputs.redis", WorkConfigurationMode.RESOLVED);

        assertThat(result.settings()).isNull();
        assertThat(result.problems()).extracting(problem -> problem.path())
            .contains("outputs.redis.port", "outputs.redis.maxLen");
    }

    @Test
    void defersAuthoringExpressionsWithoutPartialSettings() {
        var values = new java.util.LinkedHashMap<>(settings());
        values.put("host", "{{ redisHost }}");

        var result = parser.validateRedisOutputSettings(values, "outputs.redis", WorkConfigurationMode.AUTHORING);

        assertThat(result.settings()).isNull();
        assertThat(result.problems()).isEmpty();
        assertThat(result.deferredPaths()).contains("outputs.redis.host");
    }

    @Test
    void providerDelegatesToCompleteOutputParser() {
        var result = new RedisWorkOutputSettingsParser(parser)
            .validate(settings(), "outputs.redis", WorkConfigurationMode.RESOLVED);

        assertThat(result.settings()).isInstanceOf(RedisOutputSettings.class);
        assertThat(result.problems()).isEmpty();
    }

    private static Map<String, Object> settings() {
        return Map.of(
            "host", "redis",
            "port", 6379,
            "ssl", false,
            "sourceStep", "LAST",
            "pushDirection", "RPUSH",
            "maxLen", -1,
            "routes", List.of(),
            "defaultList", "ph:out",
            "targetListTemplate", ""
        );
    }
}
