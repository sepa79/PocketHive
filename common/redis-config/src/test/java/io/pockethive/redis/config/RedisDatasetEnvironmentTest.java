package io.pockethive.redis.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RedisDatasetEnvironmentTest {
    private final RedisDatasetEnvironment environment = new RedisDatasetEnvironment();

    @Test
    void preservesRawDeclarationsAndAppliesExplicitOverridesToTheCandidate() {
        var declared = new LinkedHashMap<String, Object>();
        declared.put("host", 123);
        declared.put("port", 6379);
        declared.put("ssl", false);
        declared.put("listName", "dataset");
        declared.put("pickStrategy", "ROUND_ROBIN");
        declared.put("ratePerSec", 2.0);
        declared.put("sources", List.of());

        var candidate = environment.candidate(Map.of("redis", declared), Map.of(
            "pockethive.inputs.redis.list-name", "override", "pockethive.inputs.redis.password", "")::get);

        assertThat(candidate).containsEntry("listName", "override").doesNotContainKeys("host", "port", "password", "ssl");
        assertThat(environment.encode(candidate)).containsEntry("POCKETHIVE_INPUTS_REDIS_LISTNAME", "override")
            .containsEntry("POCKETHIVE_INPUTS_REDIS_RATEPERSEC", "2");
        assertThat(declared).containsEntry("host", 123);
    }

    @Test
    void encodesSourcesAndResolvesAllTextThroughTheFrozenEnvironment() {
        var candidate = environment.candidate(Map.of("type", "REDIS_DATASET", "redis", Map.of(
            "host", "${HOST}", "port", 6379, "ssl", false,
            "sources", List.of(Map.of("listName", "${SOURCE}", "weight", "${WEIGHT}")),
            "pickStrategy", "ROUND_ROBIN", "ratePerSec", "${RATE}")), ignored -> null);
        assertThat(environment.encode(candidate)).containsEntry("POCKETHIVE_INPUTS_REDIS_SOURCES_0_LISTNAME", "${SOURCE}")
            .containsEntry("POCKETHIVE_INPUTS_REDIS_SOURCES_0_WEIGHT", "${WEIGHT}");

        var properties = Map.of(
            "pockethive.inputs.type", "REDIS_DATASET", "pockethive.inputs.redis.host", "redis",
            "pockethive.inputs.redis.sources[0].list-name", "source",
            "pockethive.inputs.redis.sources[0].weight", "2.5", "pockethive.inputs.redis.rate-per-sec", "3",
            "pockethive.inputs.redis.pick-strategy", "ROUND_ROBIN");
        var bootstrap = environment.resolve(Map.of("inputs", Map.of("type", "REDIS_DATASET", "redis", Map.of(
            "host", "redis", "port", 6379, "ssl", false))),
            candidate, properties::get);

        Map<Object, Object> redis = new LinkedHashMap<>((Map<?, ?>) ((Map<?, ?>) bootstrap.get("inputs")).get("redis"));
        assertThat(redis).containsEntry("host", "redis").containsEntry("listName", null)
            .containsEntry("ratePerSec", 3.0).containsEntry("initialDelayMs", 0L).containsEntry("tickIntervalMs", 1000L);
        assertThat((List<Object>) redis.get("sources")).containsExactly(Map.of("listName", "source", "weight", 2.5));
    }
}
