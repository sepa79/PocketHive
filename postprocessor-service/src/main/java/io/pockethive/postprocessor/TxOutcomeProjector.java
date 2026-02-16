package io.pockethive.postprocessor;

import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.api.WorkerInfo;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

final class TxOutcomeProjector {

  private static final String CALL_ID_HEADER = "x-ph-call-id";
  private static final String PROCESSOR_STATUS_HEADER = "x-ph-processor-status";
  private static final String PROCESSOR_SUCCESS_HEADER = "x-ph-processor-success";
  private static final String PROCESSOR_DURATION_HEADER = "x-ph-processor-duration-ms";
  private static final String BUSINESS_CODE_HEADER = "x-ph-business-code";
  private static final String BUSINESS_SUCCESS_HEADER = "x-ph-business-success";
  private static final String DIMENSION_PREFIX = "x-ph-dim-";
  private static final DateTimeFormatter EVENT_TIME_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

  private TxOutcomeProjector() {
  }

  static Optional<TxOutcomeEvent> project(
      WorkItem item,
      WorkerContext context,
      Instant eventTime,
      boolean dropWithoutCallId
  ) {
    Map<String, Object> headers = item.headers();
    Map<String, Object> stepHeaders = item.stepHeaders();
    String callId = normalize(readHeader(headers, stepHeaders, CALL_ID_HEADER));
    if (dropWithoutCallId && callId.isEmpty()) {
      return Optional.empty();
    }

    WorkerInfo info = context.info();
    String traceId = item.observabilityContext()
        .map(observability -> observability.getTraceId())
        .orElse("");
    int processorStatus = parseInt(readHeader(stepHeaders, headers, PROCESSOR_STATUS_HEADER), -1);
    int processorSuccess = parseBoolean(readHeader(stepHeaders, headers, PROCESSOR_SUCCESS_HEADER)) ? 1 : 0;
    long processorDurationMs = parseLong(readHeader(stepHeaders, headers, PROCESSOR_DURATION_HEADER), 0L);
    String businessCode = normalize(readHeader(stepHeaders, headers, BUSINESS_CODE_HEADER));
    int businessSuccess = parseBoolean(readHeader(stepHeaders, headers, BUSINESS_SUCCESS_HEADER)) ? 1 : 0;
    Map<String, String> dimensions = extractDimensions(stepHeaders, headers);

    return Optional.of(new TxOutcomeEvent(
        EVENT_TIME_FORMAT.format(eventTime),
        info.swarmId(),
        info.role(),
        info.instanceId(),
        traceId,
        callId,
        processorStatus,
        processorSuccess,
        processorDurationMs,
        businessCode,
        businessSuccess,
        dimensions));
  }

  private static Map<String, String> extractDimensions(Map<String, Object> primary, Map<String, Object> fallback) {
    Map<String, String> dimensions = new LinkedHashMap<>();
    collectDimensions(primary, dimensions);
    collectDimensions(fallback, dimensions);
    return Map.copyOf(dimensions);
  }

  private static void collectDimensions(Map<String, Object> headers, Map<String, String> dimensions) {
    if (headers == null || headers.isEmpty()) {
      return;
    }
    for (Map.Entry<String, Object> entry : headers.entrySet()) {
      String key = entry.getKey();
      if (key == null || !key.toLowerCase(Locale.ROOT).startsWith(DIMENSION_PREFIX)) {
        continue;
      }
      String value = normalize(stringValue(entry.getValue()));
      if (value.isEmpty()) {
        continue;
      }
      String name = key.substring(DIMENSION_PREFIX.length()).trim().toLowerCase(Locale.ROOT);
      if (name.isEmpty()) {
        continue;
      }
      dimensions.putIfAbsent(name, value);
    }
  }

  private static String readHeader(Map<String, Object> primary, Map<String, Object> fallback, String name) {
    String primaryValue = findHeaderValue(primary, name);
    if (primaryValue != null && !primaryValue.isBlank()) {
      return primaryValue;
    }
    String fallbackValue = findHeaderValue(fallback, name);
    return fallbackValue == null ? "" : fallbackValue;
  }

  private static String findHeaderValue(Map<String, Object> headers, String name) {
    if (headers == null || headers.isEmpty()) {
      return null;
    }
    for (Map.Entry<String, Object> entry : headers.entrySet()) {
      String key = entry.getKey();
      if (key != null && key.equalsIgnoreCase(name)) {
        return stringValue(entry.getValue());
      }
    }
    return null;
  }

  private static int parseInt(String value, int fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException ex) {
      return fallback;
    }
  }

  private static long parseLong(String value, long fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      return Math.max(0L, Long.parseLong(value.trim()));
    } catch (NumberFormatException ex) {
      return fallback;
    }
  }

  private static boolean parseBoolean(String value) {
    if (value == null || value.isBlank()) {
      return false;
    }
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    return "true".equals(normalized) || "1".equals(normalized);
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim();
  }

  private static String stringValue(Object value) {
    if (value == null) {
      return "";
    }
    if (value instanceof Iterable<?> iterable) {
      for (Object item : iterable) {
        if (item != null) {
          return String.valueOf(item);
        }
      }
      return "";
    }
    return String.valueOf(value);
  }
}
