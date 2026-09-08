package io.pockethive.templating;

import io.pockethive.templating.api.SequenceAccess;

import java.lang.reflect.Method;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.expression.ExpressionParser;
import org.springframework.expression.TypeLocator;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
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
 * <p>
 * Responsibility: evaluate constrained template expressions against explicit functions.
 * Must not: look up clients or configuration from global state.
 * Contract: RESP-TEMPLATE-RENDER — docs/architecture/runtime-responsibilities.md#resp-template-render.
 */
final class SpelTemplateEvaluator {

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
  private final java.lang.invoke.MethodHandle sequenceMethod;
  private final java.lang.invoke.MethodHandle sequenceWithMethod;
  private final java.lang.invoke.MethodHandle resetSequenceMethod;

  SpelTemplateEvaluator(SequenceAccess sequences) {
    SequenceFunctions functions = new SequenceFunctions(sequences);
    try {
      var lookup = java.lang.invoke.MethodHandles.lookup();
      sequenceMethod = lookup.unreflect(SequenceFunctions.class.getMethod("sequence", String.class, String.class, String.class)).bindTo(functions);
      sequenceWithMethod = lookup.unreflect(SequenceFunctions.class.getMethod("sequence", String.class, String.class, String.class, Long.class, Long.class)).bindTo(functions);
      resetSequenceMethod = lookup.unreflect(SequenceFunctions.class.getMethod("resetSequence", String.class)).bindTo(functions);
    } catch (ReflectiveOperationException ex) {
      throw new IllegalStateException("Sequence function binding failed", ex);
    }
  }

  private static final Method DATETIME_OFFSET_METHOD = Objects.requireNonNull(
    ReflectionUtils.findMethod(SpelFunctions.class, "datetimeOffset", String.class, String.class),
    "datetimeOffset method missing");

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
    context.registerFunction("sequence", sequenceMethod);
    context.registerFunction("sequenceWith", sequenceWithMethod);
    context.registerFunction("resetSequence", resetSequenceMethod);
    context.registerFunction("datetime_offset", DATETIME_OFFSET_METHOD);

    return PARSER.parseExpression(expression).getValue(context);
  }

  void validate(String expression) {
    if (expression != null && !expression.isBlank()) {
      PARSER.parseExpression(expression);
    }
  }

}
