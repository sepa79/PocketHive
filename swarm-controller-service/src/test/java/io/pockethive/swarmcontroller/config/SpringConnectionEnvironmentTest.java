package io.pockethive.swarmcontroller.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SpringConnectionEnvironmentTest {
    @ParameterizedTest
    @ValueSource(strings = {"${MISSING_CONNECTION_SECRET}", "${SPRING_RABBITMQ_PASSWORD}"})
    void rawLookupPreservesReferencesButFinalBindingRejectsUnresolvedOrCyclicValues(String reference) {
        var value = "synthetic-secret-" + reference;
        var environment = Map.of("SPRING_RABBITMQ_PASSWORD", value);

        assertThat(SpringConnectionEnvironment.raw(environment).apply("spring.rabbitmq.password")).isEqualTo(value);
        assertThatThrownBy(() -> SpringConnectionEnvironment.resolved(environment).apply("spring.rabbitmq.password"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("spring.rabbitmq.password")
            .hasMessageNotContaining("synthetic-secret-").hasNoCause();
    }
}
