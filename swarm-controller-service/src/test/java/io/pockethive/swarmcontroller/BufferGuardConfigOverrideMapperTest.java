package io.pockethive.swarmcontroller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.manager.guard.BufferGuardSettings;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class BufferGuardConfigOverrideMapperTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void disabledOverrideRemovesTheGuard() throws Exception {
    JsonNode override = mapper.readTree("{\"enabled\":false}");

    assertThat(BufferGuardConfigOverrideMapper.apply(settings(), override)).isNull();
  }

  @Test
  void normalizesEverySupportedSectionAndRetainsMissingSettings() throws Exception {
    JsonNode override = mapper.readTree("""
        {
          "targetDepth": 220,
          "samplePeriod": "pt7s",
          "adjust": {"maxIncreasePct": 11, "minRatePerSec": 2},
          "prefill": {"enabled": false, "lookahead": "pt3s"},
          "backpressure": {"queueAlias": "alternate-input", "highDepth": 330}
        }
        """);

    BufferGuardSettings updated = BufferGuardConfigOverrideMapper.apply(settings(), override);

    assertThat(updated.targetDepth()).isEqualTo(220);
    assertThat(updated.minDepth()).isEqualTo(150);
    assertThat(updated.samplePeriod()).isEqualTo(Duration.ofSeconds(7));
    assertThat(updated.adjust().maxIncreasePct()).isEqualTo(11);
    assertThat(updated.adjust().maxDecreasePct()).isEqualTo(20);
    assertThat(updated.adjust().minRatePerSec()).isEqualTo(2);
    assertThat(updated.prefill().enabled()).isFalse();
    assertThat(updated.prefill().lookahead()).isEqualTo(Duration.ofSeconds(3));
    assertThat(updated.prefill().liftPct()).isEqualTo(30);
    assertThat(updated.backpressure().queueAlias()).isEqualTo("alternate-input");
    assertThat(updated.backpressure().queueName()).isEqualTo("ph-test.moderator-input");
    assertThat(updated.backpressure().highDepth()).isEqualTo(330);
    assertThat(updated.backpressure().recoveryDepth()).isEqualTo(180);
  }

  @Test
  void rejectsRuntimeChangeOfTheGuardQueueIdentity() throws Exception {
    JsonNode override = mapper.readTree("{\"queueAlias\":\"other-output\"}");

    assertThatThrownBy(() -> BufferGuardConfigOverrideMapper.apply(settings(), override))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("queueAlias");
  }

  @Test
  void rejectsPresentFieldWithWrongTypeInsteadOfRetainingCurrentValue() throws Exception {
    JsonNode override = mapper.readTree("{\"targetDepth\":\"invalid\"}");

    assertThatThrownBy(() -> BufferGuardConfigOverrideMapper.apply(settings(), override))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("targetDepth");
  }

  private static BufferGuardSettings settings() {
    return new BufferGuardSettings(
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
        new BufferGuardSettings.Backpressure(
            "moderator-input", "ph-test.moderator-input", 300, 180, 40));
  }
}
