package io.pockethive.processor;

import io.pockethive.swarm.model.ResultRules;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ResultRulesExtractor {

  public static final String HEADER_BUSINESS_CODE = "x-ph-business-code";
  public static final String HEADER_BUSINESS_SUCCESS = "x-ph-business-success";
  public static final String HEADER_DIMENSION_PREFIX = "x-ph-dim-";

  private ResultRulesExtractor() {
  }

  public static Map<String, Object> extract(
      ResultRules rules,
      String requestBody,
      Map<String, ?> requestHeaders,
      String responseBody,
      Map<String, ?> responseHeaders
  ) {
    if (rules == null) {
      return Map.of();
    }

    Map<String, Object> extracted = new LinkedHashMap<>();
    String businessCode = null;
    if (rules.businessCode() != null) {
      businessCode = extractValue(
          rules.businessCode().source(),
          rules.businessCode().pattern(),
          rules.businessCode().header(),
          requestBody,
          requestHeaders,
          responseBody,
          responseHeaders);
      if (businessCode != null && !businessCode.isBlank()) {
        extracted.put(HEADER_BUSINESS_CODE, businessCode);
      }
    }

    if (rules.successRegex() != null
        && !rules.successRegex().isBlank()
        && businessCode != null
        && !businessCode.isBlank()) {
      extracted.put(
          HEADER_BUSINESS_SUCCESS,
          Boolean.toString(patternFor(rules.successRegex()).matcher(businessCode).find()));
    }

    if (rules.dimensions() != null && !rules.dimensions().isEmpty()) {
      for (ResultRules.DimensionExtractor dimension : rules.dimensions()) {
        if (dimension == null) {
          throw new IllegalArgumentException("ResultRules dimension entry must not be null");
        }
        if (dimension.name() == null || dimension.name().isBlank()) {
          throw new IllegalArgumentException("ResultRules dimension name must not be blank");
        }
        String value = extractValue(
            dimension.source(),
            dimension.pattern(),
            dimension.header(),
            requestBody,
            requestHeaders,
            responseBody,
            responseHeaders);
        if (value == null || value.isBlank()) {
          continue;
        }
        String key = HEADER_DIMENSION_PREFIX + normaliseDimensionName(dimension.name());
        if (extracted.containsKey(key)) {
          throw new IllegalArgumentException("Duplicate ResultRules dimension after normalization: " + key);
        }
        extracted.put(key, value);
      }
    }

    if (extracted.isEmpty()) {
      return Map.of();
    }
    return Map.copyOf(extracted);
  }

  private static String extractValue(
      ResultRules.Source source,
      String pattern,
      String headerName,
      String requestBody,
      Map<String, ?> requestHeaders,
      String responseBody,
      Map<String, ?> responseHeaders
  ) {
    if (source == null) {
      throw new IllegalArgumentException("ResultRules extractor source must not be null");
    }
    if (pattern == null || pattern.isBlank()) {
      throw new IllegalArgumentException("ResultRules extractor pattern must not be blank");
    }
    if ((source == ResultRules.Source.REQUEST_HEADER || source == ResultRules.Source.RESPONSE_HEADER)
        && (headerName == null || headerName.isBlank())) {
      throw new IllegalArgumentException("ResultRules extractor header must not be blank for " + source);
    }
    String input = switch (source) {
      case REQUEST_BODY -> requestBody;
      case RESPONSE_BODY -> responseBody;
      case REQUEST_HEADER -> findHeaderValue(requestHeaders, headerName);
      case RESPONSE_HEADER -> findHeaderValue(responseHeaders, headerName);
    };
    if (input == null) {
      return null;
    }
    Matcher matcher = patternFor(pattern).matcher(input);
    if (!matcher.find()) {
      return null;
    }
    if (matcher.groupCount() < 1) {
      return null;
    }
    return matcher.group(1);
  }

  // Intentionally fail-loud: invalid regex patterns must fail the call rather than being ignored.
  private static Pattern patternFor(String regex) {
    return PatternCache.INSTANCE.get(regex);
  }

  private static final class PatternCache {
    private static final PatternCache INSTANCE = new PatternCache(1024);

    private final int maxSize;
    private final LinkedHashMap<String, Pattern> cache;

    private PatternCache(int maxSize) {
      this.maxSize = Math.max(16, maxSize);
      this.cache = new LinkedHashMap<>(this.maxSize, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Pattern> eldest) {
          return size() > PatternCache.this.maxSize;
        }
      };
    }

    private Pattern get(String regex) {
      if (regex == null) {
        throw new IllegalArgumentException("regex must not be null");
      }
      String key = regex.trim();
      if (key.isEmpty()) {
        throw new IllegalArgumentException("regex must not be blank");
      }
      synchronized (cache) {
        Pattern existing = cache.get(key);
        if (existing != null) {
          return existing;
        }
        Pattern compiled = Pattern.compile(key);
        cache.put(key, compiled);
        return compiled;
      }
    }
  }

  private static String findHeaderValue(Map<String, ?> headers, String headerName) {
    if (headers == null || headers.isEmpty() || headerName == null || headerName.isBlank()) {
      return null;
    }
    Object direct = headers.get(headerName);
    if (direct != null) {
      return stringValue(direct);
    }
    String lowered = headerName.toLowerCase(Locale.ROOT);
    for (Map.Entry<String, ?> entry : headers.entrySet()) {
      if (entry.getKey() != null
          && entry.getKey().toLowerCase(Locale.ROOT).equals(lowered)) {
        return stringValue(entry.getValue());
      }
    }
    return null;
  }

  private static String stringValue(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Iterable<?> iterable) {
      for (Object item : iterable) {
        if (item != null) {
          return String.valueOf(item);
        }
      }
      return null;
    }
    return String.valueOf(value);
  }

  private static String normaliseDimensionName(String name) {
    String trimmed = name.trim().toLowerCase(Locale.ROOT);
    return trimmed.replaceAll("[^a-z0-9_-]", "-");
  }
}
