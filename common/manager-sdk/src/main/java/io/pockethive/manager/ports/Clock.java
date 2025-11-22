package io.pockethive.manager.ports;

import java.time.Instant;

/**
 * Pluggable clock abstraction used for timing and expiry logic.
 */
public interface Clock {

  long currentTimeMillis();

  Instant now();

  static Clock system() {
    return new Clock() {
      @Override
      public long currentTimeMillis() {
        return System.currentTimeMillis();
      }

      @Override
      public Instant now() {
        return Instant.now();
      }
    };
  }
}

