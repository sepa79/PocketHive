package io.pockethive.moderator.shaper.config;

public record RepeatConfig(boolean enabled, RepeatUntil until, Integer occurrences, RepeatAlignment align) {

  public RepeatConfig {
    until = until == null ? RepeatUntil.TOTAL_TIME : until;
    align = align == null ? RepeatAlignment.FROM_START : align;
    if (until == RepeatUntil.OCCURRENCES) {
      if (occurrences == null || occurrences <= 0) {
        throw new IllegalArgumentException("occurrences must be positive when until is OCCURRENCES");
      }
    } else {
      occurrences = null;
    }
  }
}
