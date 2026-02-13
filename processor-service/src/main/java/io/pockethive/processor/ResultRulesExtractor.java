package io.pockethive.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.httpbuilder.HttpTemplateDefinition;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ResultRulesExtractor {

  private static final String HEADER_BUSINESS_CODE = "x-ph-business-code";
  private static final String HEADER_BUSINESS_SUCCESS = "x-ph-business-success";
  private static final String HEADER_DIMENSION_PREFIX = "x-ph-dim-";

  private ResultRulesExtractor() {
  }

  public static Map<String, Object> extract(
      ObjectMapper mapper,
      JsonNode envelope,
      String requestBody,
      Map<String, ?> requestHeaders,
      String responseBody,
      Map<String, ?> responseHeaders
  ) {
    HttpTemplateDefinition.ResultRules rules = parseRules(mapper, envelope);
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
    if (rules.successRegex() != null && !rules.successRegex().isBlank() && businessCode != null && !businessCode.isBlank()) {
      extracted.put(HEADER_BUSINESS_SUCCESS, Boolean.toString(Pattern.compile(rules.successRegex()).matcher(businessCode).find()));
    }
    if (rules.dimensions() != null && !rules.dimensions().isEmpty()) {
      for (HttpTemplateDefinition.DimensionExtractor dimension : rules.dimensions()) {
        if (dimension == null || dimension.name() == null || dimension.name().isBlank()) {
          continue;
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
        extracted.put(HEADER_DIMENSION_PREFIX + normaliseDimensionName(dimension.name()), value);
      }
    }
    if (extracted.isEmpty()) {
      return Map.of();
    }
    return Map.copyOf(extracted);
  }

  private static HttpTemplateDefinition.ResultRules parseRules(ObjectMapper mapper, JsonNode envelope) {
    if (mapper == null || envelope == null) {
      return null;
    }
    JsonNode node = envelope.path("resultRules");
    if (node.isMissingNode() || node.isNull() || !node.isObject()) {
      return null;
    }
    try {
      return mapper.treeToValue(node, HttpTemplateDefinition.ResultRules.class);
    } catch (Exception ignored) {
      return null;
    }
  }

  private static String extractValue(
      HttpTemplateDefinition.Source source,
      String pattern,
      String headerName,
      String requestBody,
      Map<String, ?> requestHeaders,
      String responseBody,
      Map<String, ?> responseHeaders
  ) {
    if (source == null || pattern == null || pattern.isBlank()) {
      return null;
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
    Matcher matcher = Pattern.compile(pattern).matcher(input);
    if (!matcher.find()) {
      return null;
    }
    if (matcher.groupCount() < 1) {
      return null;
    }
    return matcher.group(1);
  }

  private static String findHeaderValue(Map<String, ?> headers, String headerName) {
    if (headers == null || headers.isEmpty() || headerName == null || headerName.isBlank()) {
      return null;
    }
    Object direct = headers.get(headerName);
    if (direct != null) {
      return stringValue(direct);
    }
    String lowered = headerName.toLowerCase(java.util.Locale.ROOT);
    for (Map.Entry<String, ?> entry : headers.entrySet()) {
      if (entry.getKey() != null
          && entry.getKey().toLowerCase(java.util.Locale.ROOT).equals(lowered)) {
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
