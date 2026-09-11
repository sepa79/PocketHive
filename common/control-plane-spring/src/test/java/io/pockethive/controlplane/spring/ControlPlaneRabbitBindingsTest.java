package io.pockethive.controlplane.spring;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ControlPlaneRabbitBindingsTest {
    @Test
    void rejectsContractErrorsThroughNestedCausesButLeavesTransientFailuresToTransport() {
        var binding = new ControlPlaneRabbitBindings(true).bind("listener", "queue", message -> {});
        assertThat(binding.rejectWithoutRequeue().test(new RuntimeException(new IllegalArgumentException("invalid")))).isTrue();
        assertThat(binding.rejectWithoutRequeue().test(new RuntimeException(new JsonProcessingException("invalid") {}))).isTrue();
        assertThat(binding.rejectWithoutRequeue().test(new IllegalStateException("temporarily unavailable"))).isFalse();
    }

    @Test
    void disabledPoisonPolicyDoesNotOverrideTransportDecision() {
        var binding = new ControlPlaneRabbitBindings(false).bind("listener", "queue", message -> {});
        assertThat(binding.rejectWithoutRequeue().test(new IllegalArgumentException("invalid"))).isFalse();
    }
}
