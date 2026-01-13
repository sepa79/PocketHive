package io.pockethive.worker.sdk.templating;

import java.lang.reflect.Method;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.expression.ExpressionParser;
import org.springframework.expression.TypeLocator;
import org.springframework.expression.PropertyAccessor;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.TypedValue;
import org.springframework.expression.spel.SpelEvaluationException;
import org.springframework.expression.spel.SpelMessage;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.expression.spel.support.StandardTypeLocator;
import org.springframework.util.ReflectionUtils;

/**
 * Constrained SpEL evaluator exposing a small set of helpers for templating.
 * <p>
 * Supported root variables:
 * <ul>
 *   <li>{@code workItem} – full WorkItem (if provided)</li>
 *   <li>{@code payload} – current payload</li>
 *   <li>{@code headers} – current headers map</li>
 *   <li>{@code now} – {@link Instant#now()}</li>
 *   <li>{@code nowIso} – ISO-8601 string of now (UTC)</li>
 * </ul>
 * Supported functions:
 * <ul>
 *   <li>{@code #randInt(min, max)} – random integer in the inclusive range</li>
 *   <li>{@code #randLong(min, max)} – random long in the inclusive range (pass numbers as strings to avoid parser limits)</li>
 *   <li>{@code #uuid()} – random UUID string</li>
 *   <li>{@code #md5_hex(value)} – lower-case MD5 digest</li>
 *   <li>{@code #sha256_hex(value)} – lower-case SHA-256 digest</li>
 *   <li>{@code #base64_encode(value)} / {@code #base64_decode(value)} – Base64 helpers (UTF-8)</li>
 *   <li>{@code #hmac_sha256_hex(key, value)} – HMAC-SHA256 hex</li>
 *   <li>{@code #regex_match(input, pattern)} – boolean match</li>
 *   <li>{@code #regex_extract(input, pattern, group)} – matched group or empty string</li>
 *   <li>{@code #json_path(payload, path)} – JSON-Pointer lookup, string result</li>
 *   <li>{@code #date_format(instant, pattern)} – formats the provided {@link Instant} (or now if null)</li>
 *   <li>{@code #sequence(key, mode, format)} – generates unique sequences from Redis (mode: alpha/numeric/alphanum/binary/hex)</li>
 * </ul>
 */
final class SpelTemplateEvaluator {

  private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
  private static final ExpressionParser PARSER = new SpelExpressionParser();
  private static final TypeLocator BLOCKING_TYPE_LOCATOR = new BlockingTypeLocator();

  private static final Method RAND_INT_METHOD = Objects.requireNonNull(
    ReflectionUtils.findMethod(SpelFunctions.class, "randInt", int.class, int.class),
    "randInt method missing");
  private static final Method RAND_LONG_METHOD = Objects.requireNonNull(
    ReflectionUtils.findMethod(SpelFunctions.class, "randLong", String.class, String.class),
    "randLong method missing");
  private static final Method UUID_METHOD = Objects.requireNonNull(
    ReflectionUtils.findMethod(SpelFunctions.class, "uuid"),
    "uuid method missing");
  private static final Method MD5_HEX_METHOD = Objects.requireNonNull(
    ReflectionUtils.findMethod(SpelFunctions.class, "md5Hex", String.class),
    "md5Hex method missing");
  private static final Method SHA256_HEX_METHOD = Objects.requireNonNull(
    ReflectionUtils.findMethod(SpelFunctions.class, "sha256Hex", String.class),
    "sha256Hex method missing");
  private static final Method BASE64_ENCODE_METHOD = Objects.requireNonNull(
    ReflectionUtils.findMethod(SpelFunctions.class, "base64Encode", String.class),
    "base64Encode method missing");
  private static final Method BASE64_DECODE_METHOD = Objects.requireNonNull(
    ReflectionUtils.findMethod(SpelFunctions.class, "base64Decode", String.class),
    "base64Decode method missing");
  private static final Method HMAC_SHA256_METHOD = Objects.requireNonNull(
    ReflectionUtils.findMethod(SpelFunctions.class, "hmacSha256Hex", String.class, String.class),
    "hmacSha256Hex method missing");
  private static final Method REGEX_MATCH_METHOD = Objects.requireNonNull(
    ReflectionUtils.findMethod(SpelFunctions.class, "regexMatch", String.class, String.class),
    "regexMatch method missing");
  private static final Method REGEX_EXTRACT_METHOD = Objects.requireNonNull(
    ReflectionUtils.findMethod(SpelFunctions.class, "regexExtract", String.class, String.class, int.class),
    "regexExtract method missing");
  private static final Method JSON_PATH_METHOD = Objects.requireNonNull(
    ReflectionUtils.findMethod(SpelFunctions.class, "jsonPath", Object.class, String.class),
    "jsonPath method missing");
  private static final Method DATE_FORMAT_METHOD = Objects.requireNonNull(
    ReflectionUtils.findMethod(SpelFunctions.class, "dateFormat", Object.class, String.class),
    "dateFormat method missing");
  private static final Method SEQUENCE_METHOD = Objects.requireNonNull(
    ReflectionUtils.findMethod(SpelFunctions.class, "sequence", String.class, String.class, String.class),
    "sequence method missing");
  private static final Method SEQUENCE_WITH_METHOD = Objects.requireNonNull(
    ReflectionUtils.findMethod(SpelFunctions.class, "sequence", String.class, String.class, String.class, Long.class, Long.class),
    "sequence with options method missing");
  private static final Method RESET_SEQUENCE_METHOD = Objects.requireNonNull(
    ReflectionUtils.findMethod(SpelFunctions.class, "resetSequence", String.class),
    "resetSequence method missing");

  Object evaluate(String expression, Map<String, Object> rootValues) {
    if (expression == null || expression.isBlank()) {
      return "";
    }
    Map<String, Object> variables = rootValues == null ? Map.of() : rootValues;
    StandardEvaluationContext context = new StandardEvaluationContext(variables);
    context.setTypeLocator(BLOCKING_TYPE_LOCATOR);
    context.setPropertyAccessors(List.of(new MapEntryAccessor()));
    context.setMethodResolvers(List.of());
    context.setConstructorResolvers(List.of());
    context.setBeanResolver(null);
    context.registerFunction("randInt", RAND_INT_METHOD);
    context.registerFunction("randLong", RAND_LONG_METHOD);
    context.registerFunction("uuid", UUID_METHOD);
    context.registerFunction("md5_hex", MD5_HEX_METHOD);
    context.registerFunction("sha256_hex", SHA256_HEX_METHOD);
    context.registerFunction("base64_encode", BASE64_ENCODE_METHOD);
    context.registerFunction("base64_decode", BASE64_DECODE_METHOD);
    context.registerFunction("hmac_sha256_hex", HMAC_SHA256_METHOD);
    context.registerFunction("regex_match", REGEX_MATCH_METHOD);
    context.registerFunction("regex_extract", REGEX_EXTRACT_METHOD);
    context.registerFunction("json_path", JSON_PATH_METHOD);
    context.registerFunction("date_format", DATE_FORMAT_METHOD);
    context.registerFunction("sequence", SEQUENCE_METHOD);
    context.registerFunction("sequenceWith", SEQUENCE_WITH_METHOD);
    context.registerFunction("resetSequence", RESET_SEQUENCE_METHOD);

    return PARSER.parseExpression(expression).getValue(context);
  }

  private static final class BlockingTypeLocator extends StandardTypeLocator {
    @Override
    public Class<?> findType(String typeName) {
      throw new SpelEvaluationException(SpelMessage.TYPE_NOT_FOUND, typeName);
    }
  }

  private static final class MapEntryAccessor implements PropertyAccessor {
    @Override
    public Class<?>[] getSpecificTargetClasses() {
      return new Class[]{Map.class};
    }

    @Override
    public boolean canRead(EvaluationContext context, Object target, String name) {
      return target instanceof Map<?, ?>;
    }

    @Override
    public TypedValue read(EvaluationContext context, Object target, String name) {
      Map<?, ?> map = (Map<?, ?>) target;
      Object value = map.get(name);
      return new TypedValue(value);
    }

    @Override
    public boolean canWrite(EvaluationContext context, Object target, String name) {
      return false;
    }

    @Override
    public void write(EvaluationContext context, Object target, String name, Object newValue) {
      throw new UnsupportedOperationException("read-only map accessor");
    }
  }

  private static final class SpelFunctions {
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

    static String sequence(String key, String mode, String format) {
      if (key == null || key.isBlank()) throw new IllegalArgumentException("key required");
      if (mode == null || mode.isBlank()) throw new IllegalArgumentException("mode required");
      if (format == null || format.isBlank()) throw new IllegalArgumentException("format required");
      return RedisSequenceGenerator.getDefaultInstance().next(key, mode, format, 1, -1);
    }

    static String sequence(String key, String mode, String format, Long startOffset, Long maxSequence) {
      if (key == null || key.isBlank()) throw new IllegalArgumentException("key required");
      if (mode == null || mode.isBlank()) throw new IllegalArgumentException("mode required");
      if (format == null || format.isBlank()) throw new IllegalArgumentException("format required");
      long start = startOffset != null ? startOffset : 1;
      long max = maxSequence != null ? maxSequence : -1;
      if (start < 1) throw new IllegalArgumentException("startOffset must be >= 1");
      return RedisSequenceGenerator.getDefaultInstance().next(key, mode, format, start, max);
    }

    static boolean resetSequence(String key) {
      if (key == null || key.isBlank()) throw new IllegalArgumentException("key required");
      return RedisSequenceGenerator.getDefaultInstance().reset(key);
    }
  }
}
