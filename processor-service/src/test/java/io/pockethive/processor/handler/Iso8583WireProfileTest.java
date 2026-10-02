package io.pockethive.processor.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class Iso8583WireProfileTest {
  @Test
  void framesFullTwoByteLengthRangeAndRejectsOverflow() {
    var profile = Iso8583WireProfile.MC_2BYTE_LEN_BIN_BITMAP;
    byte[] payload = new byte[profile.maxPayloadBytes()];
    byte[] frame = profile.frame(payload);
    assertThat(frame).hasSize(profile.maxPayloadBytes() + Iso8583WireProfile.LENGTH_PREFIX_BYTES);
    assertThat(frame[0]).isEqualTo((byte) 0xFF);
    assertThat(frame[1]).isEqualTo((byte) 0xFF);
    assertThatThrownBy(() -> profile.frame(new byte[profile.maxPayloadBytes() + 1]))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exceeds 65535");
    assertThatThrownBy(() -> Iso8583WireProfile.fromId("unknown"))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unsupported");
  }
}
