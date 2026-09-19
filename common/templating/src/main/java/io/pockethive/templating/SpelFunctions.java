package io.pockethive.templating;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Responsibility: implement template value helpers, including clock and random functions.
 * Must not: access sequence clients or select infrastructure.
 * Contract: RESP-TEMPLATE-RENDER — docs/architecture/runtime-responsibilities.md#resp-template-render.
 */
final class SpelFunctions {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
  private SpelFunctions() {
  }

  static int randInt(int min, int max) {
    long span = (long) max - (long) min + 1;
    if (span <= 0) {
      throw new IllegalArgumentException("max must be >= min");
    }
    // inclusive upper bound without overflow
    long offset = ThreadLocalRandom.current().nextLong(span);
    return (int) (min + offset);
  }

  static long randLong(String minInclusive, String maxInclusive) {
    Objects.requireNonNull(minInclusive, "minInclusive");
    Objects.requireNonNull(maxInclusive, "maxInclusive");
    long min = parseLong(minInclusive, "minInclusive");
    long max = parseLong(maxInclusive, "maxInclusive");
    if (max < min) {
      throw new IllegalArgumentException("max must be >= min");
    }
    long span = max - min + 1;
    long offset = span == Long.MIN_VALUE ? 0 : ThreadLocalRandom.current().nextLong(span);
    return min + offset;
  }

  static String uuid() {
    return UUID.randomUUID().toString();
  }

  static String md5Hex(String value) {
    Objects.requireNonNull(value, "value");
    return digestHex("MD5", value);
  }

  static String sha256Hex(String value) {
    Objects.requireNonNull(value, "value");
    return digestHex("SHA-256", value);
  }

  static String base64Encode(String value) {
    Objects.requireNonNull(value, "value");
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  static String base64Decode(String value) {
    Objects.requireNonNull(value, "value");
    return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
  }

  static String hmacSha256Hex(String key, String value) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(value, "value");
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("Failed to compute HMAC-SHA256", e);
    }
  }

  static boolean regexMatch(String input, String pattern) {
    if (input == null || pattern == null) {
      return false;
    }
    return Pattern.compile(pattern, Pattern.DOTALL).matcher(input).find();
  }

  static String regexExtract(String input, String pattern, int group) {
    if (input == null || pattern == null) {
      return "";
    }
    Matcher matcher = Pattern.compile(pattern, Pattern.DOTALL).matcher(input);
    if (!matcher.find()) {
      return "";
    }
    if (group < 0 || group > matcher.groupCount()) {
      return "";
    }
    String result = matcher.group(group);
    return result == null ? "" : result;
  }

  static String jsonPath(Object payload, String path) {
    if (payload == null || path == null || path.isBlank()) {
      return "";
    }
    try {
      JsonNode node;
      if (payload instanceof String str) {
        node = MAPPER.readTree(str);
      } else {
        node = MAPPER.valueToTree(payload);
      }
      JsonNode target = node.at(path);
      if (target.isMissingNode() || target.isNull()) {
        return "";
      }
      return target.isValueNode() ? target.asText("") : target.toString();
    } catch (Exception e) {
      return "";
    }
  }

  static String dateFormat(Object instant, String pattern) {
    Objects.requireNonNull(pattern, "pattern");
    Instant base = (instant instanceof Instant inst) ? inst : Instant.now();
    DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern);
    return formatter.format(OffsetDateTime.ofInstant(base, ZoneOffset.UTC));
  }

  private static String digestHex(String algorithm, String value) {
    try {
      var digest = java.security.MessageDigest.getInstance(algorithm);
      byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(algorithm + " algorithm not available", e);
    }
  }

  private static long parseLong(String value, String label) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException(label + " is not a valid long", ex);
    }
  }

  static String datetimeOffset(String offset, String pattern) {
    Objects.requireNonNull(offset, "offset");
    Objects.requireNonNull(pattern, "pattern");
    OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC);
    Matcher m = Pattern.compile("^([+-]?)(\\d+)\\s*(s|sec|seconds?|m|min|minutes?|h|hours?|d|days?|w|weeks?|M|months?|y|years?)$")
        .matcher(offset.trim());
    if (!m.matches()) {
      throw new IllegalArgumentException("Invalid offset: '" + offset + "'. Use e.g. '+2d', '-1month', '3h'");
    }
    int sign = "-".equals(m.group(1)) ? -1 : 1;
    long amount = Long.parseLong(m.group(2)) * sign;
    String unit = m.group(3);
    OffsetDateTime target = switch (unit.charAt(0)) {
      case 's' -> base.plusSeconds(amount);
      case 'h' -> base.plusHours(amount);
      case 'd' -> base.plusDays(amount);
      case 'w' -> base.plusWeeks(amount);
      case 'y' -> base.plusYears(amount);
      case 'M' -> base.plusMonths(amount);
      default -> unit.startsWith("mo") || unit.equals("M")
          ? base.plusMonths(amount)
          : base.plusMinutes(amount);
    };
    return DateTimeFormatter.ofPattern(pattern).format(target);
  }

}
