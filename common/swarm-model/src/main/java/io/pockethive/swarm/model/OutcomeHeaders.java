package io.pockethive.swarm.model;

import java.util.Locale;
import java.util.Objects;

public final class OutcomeHeaders {

  public static final String CALL_ID = "x-ph-call-id";
  public static final String SERVICE_ID = "x-ph-service-id";
  public static final String PROCESSOR_STATUS = "x-ph-processor-status";
  public static final String PROCESSOR_SUCCESS = "x-ph-processor-success";
  public static final String PROCESSOR_DURATION_MS = "x-ph-processor-duration-ms";
  public static final String BUSINESS_CODE = "x-ph-business-code";
  public static final String BUSINESS_SUCCESS = "x-ph-business-success";
  public static final String DIMENSION_PREFIX = "x-ph-dim-";

  private OutcomeHeaders() {
  }

  public static String dimension(String name) {
    return DIMENSION_PREFIX + normalizeDimensionName(name);
  }

  public static String normalizeDimensionName(String name) {
    String trimmed = Objects.requireNonNull(name, "name").trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("Outcome dimension name must not be blank");
    }
    return trimmed.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
  }
}
