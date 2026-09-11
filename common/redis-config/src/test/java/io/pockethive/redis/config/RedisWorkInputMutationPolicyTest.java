package io.pockethive.redis.config;

import io.pockethive.work.config.WorkMutationRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisWorkInputMutationPolicyTest {
    private final RedisWorkInputMutationPolicy policy = new RedisWorkInputMutationPolicy(new RedisConfigurationParser());

    @Test
    void validatesRatePerSec() {
        assertThatCode(() -> policy.validate(request("inputs.redis.ratePerSec", 1.0, 2.5, singleSource())))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.validate(request("inputs.redis.ratePerSec", 1.0, -0.1, singleSource())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("inputs.redis.ratePerSec")
            .hasMessageContaining("finite number >= 0.0");
        assertThatThrownBy(() -> policy.validate(request("inputs.redis.ratePerSec", 1.0, null, singleSource())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("inputs.redis.ratePerSec");
    }

    @Test
    void allowsListNameOnlyFromPriorSingleSourceMode() {
        assertThatCode(() -> policy.validate(request("inputs.redis.listName", "ph:dataset", "ph:other", singleSource())))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.validate(request("inputs.redis.listName", "", "ph:other", multiSource())))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("single-source listName mode");
    }

    @Test
    void rejectsInvalidListName() {
        for (Object invalid : List.of(7, " ", " ph:other ", "{{ 'red' }}")) {
            assertThatThrownBy(() -> policy.validate(request("inputs.redis.listName", "ph:dataset", invalid, singleSource())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inputs.redis.listName");
        }
    }

    private static WorkMutationRequest request(String path, Object previousValue, Object updatedValue,
                                               Map<String, Object> previousSettings) {
        return new WorkMutationRequest(
            "testWorker", Map.of(), previousSettings, Map.of(), path, previousValue, updatedValue, false);
    }

    private static Map<String, Object> singleSource() {
        return Map.of("listName", "ph:dataset", "sources", List.of());
    }

    private static Map<String, Object> multiSource() {
        return Map.of("listName", "", "sources", List.of(Map.of("listName", "ph:dataset", "weight", 1.0)));
    }
}
