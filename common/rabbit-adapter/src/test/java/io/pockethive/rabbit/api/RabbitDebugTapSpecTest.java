package io.pockethive.rabbit.api;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RabbitDebugTapSpecTest {
    @Test
    void preservesTapQueuePolicyAndConvertsSecondsWithoutChangingTheRoute() {
        var tap = RabbitDebugTapSpec.create("tap", "hive", "route", 60, 7);
        assertThat(tap.queue()).isEqualTo(new RabbitQueueSpec("tap", false, true, true,
            Map.of("x-message-ttl", 60000L, "x-max-length", 7)));
        assertThat(tap.binding()).isEqualTo(new RabbitBindingSpec("tap", "hive", "route", Map.of()));
    }
}
