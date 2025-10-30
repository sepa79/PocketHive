package io.pockethive.moderator.shaper.config;

import java.math.BigDecimal;
import java.time.ZoneId;

public record TimeConfig(TimeMode mode, BigDecimal warpFactor, ZoneId tz) {

  private static final BigDecimal DEFAULT_WARP = BigDecimal.ONE;
  private static final ZoneId DEFAULT_ZONE = ZoneId.of("UTC");

  public TimeConfig {
    mode = mode == null ? TimeMode.WARP : mode;
    warpFactor = warpFactor == null ? DEFAULT_WARP : warpFactor;
    tz = tz == null ? DEFAULT_ZONE : tz;

    if (warpFactor.compareTo(BigDecimal.ZERO) <= 0) {
      throw new IllegalArgumentException("warpFactor must be positive");
    }
  }
}
