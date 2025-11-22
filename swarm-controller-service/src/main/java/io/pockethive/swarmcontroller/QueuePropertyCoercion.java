package io.pockethive.swarmcontroller;

import java.util.OptionalLong;

/**
 * Small helper for coercing RabbitMQ queue property values to primitive types.
 * <p>
 * Centralised here to avoid repeating the same parsing logic in multiple components.
 */
public final class QueuePropertyCoercion {

  private QueuePropertyCoercion() {
  }

  public static long coerceLong(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof String string) {
      try {
        return Long.parseLong(string);
      } catch (NumberFormatException ignored) {
        return 0L;
      }
    }
    return 0L;
  }

  public static int coerceInt(Object value) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value instanceof String string) {
      try {
        return Integer.parseInt(string);
      } catch (NumberFormatException ignored) {
        return 0;
      }
    }
    return 0;
  }

  public static OptionalLong coerceOptionalLong(Object... candidates) {
    if (candidates == null) {
      return OptionalLong.empty();
    }
    for (Object candidate : candidates) {
      if (candidate == null) {
        continue;
      }
      if (candidate instanceof Number number) {
        return OptionalLong.of(number.longValue());
      }
      if (candidate instanceof String string) {
        try {
          return OptionalLong.of(Long.parseLong(string));
        } catch (NumberFormatException ignored) {
          // try next
        }
      }
    }
    return OptionalLong.empty();
  }
}

