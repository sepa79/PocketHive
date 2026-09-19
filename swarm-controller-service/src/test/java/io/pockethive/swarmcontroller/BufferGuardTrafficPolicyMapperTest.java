package io.pockethive.swarmcontroller;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.manager.guard.BufferGuardSettings;
import io.pockethive.swarm.model.TrafficPolicy;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class BufferGuardTrafficPolicyMapperTest {

  @Test
  void mapsEveryEffectiveGuardSectionToTheCanonicalProjection() {
    BufferGuardSettings settings = new BufferGuardSettings(
        "generator-output",
        "ph-test.generator-output",
        "generator",
        20,
        200,
        150,
        260,
        Duration.ofSeconds(5),
        3,
        new BufferGuardSettings.Adjustment(10, 20, 1, 100),
        new BufferGuardSettings.Prefill(true, Duration.ofSeconds(2), 30),
        new BufferGuardSettings.Backpressure("moderator-input", "ph-test.moderator-input", 300, 180, 40));

    TrafficPolicy policy = BufferGuardTrafficPolicyMapper.toTrafficPolicy(settings);

    assertThat(policy.bufferGuard().queueAlias()).isEqualTo("generator-output");
    assertThat(policy.bufferGuard().samplePeriod()).isEqualTo("PT5S");
    assertThat(policy.bufferGuard().adjust().maxIncreasePct()).isEqualTo(10);
    assertThat(policy.bufferGuard().prefill().lookahead()).isEqualTo("PT2S");
    assertThat(policy.bufferGuard().backpressure().queueAlias()).isEqualTo("moderator-input");
  }
}
