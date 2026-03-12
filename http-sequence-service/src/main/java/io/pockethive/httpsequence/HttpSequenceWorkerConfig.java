package io.pockethive.httpsequence;

import io.pockethive.worker.sdk.config.MaxInFlightConfig;
import java.util.List;
import java.util.Map;

public record HttpSequenceWorkerConfig(
    String baseUrl,
    String templateRoot,
    String serviceId,
    int threadCount,
    List<Step> steps,
    DebugCapture debugCapture,
    Map<String, Object> vars
) implements MaxInFlightConfig {

  public HttpSequenceWorkerConfig {
    baseUrl = normalise(baseUrl);
    templateRoot = normaliseOrDefault(templateRoot, "/app/templates/http");
    serviceId = normaliseOrDefault(serviceId, "default");
    threadCount = threadCount <= 0 ? 1 : threadCount;
    steps = steps == null ? List.of() : List.copyOf(steps);
    debugCapture = debugCapture == null ? DebugCapture.defaults() : debugCapture;
    vars = vars == null ? Map.of() : Map.copyOf(vars);
  }

  @Override
  public int maxInFlight() {
    return threadCount;
  }

  public record Step(
      String id,
      String callId,
      String serviceId,
      boolean continueOnNon2xx,
      Retry retry,
      List<Extract> extracts,
      List<SetValue> set
  ) {

    public Step {
      id = normalise(id);
      callId = normalise(callId);
      serviceId = normalise(serviceId);
      retry = retry == null ? Retry.defaults() : retry;
      extracts = extracts == null ? List.of() : List.copyOf(extracts);
      set = set == null ? List.of() : List.copyOf(set);
    }
  }

  public record Retry(
      int maxAttempts,
      long initialBackoffMs,
      double backoffMultiplier,
      long maxBackoffMs,
      List<String> on
  ) {

    public Retry {
      maxAttempts = maxAttempts <= 0 ? 1 : maxAttempts;
      initialBackoffMs = Math.max(0L, initialBackoffMs);
      backoffMultiplier = backoffMultiplier <= 0.0 ? 1.0 : backoffMultiplier;
      maxBackoffMs = maxBackoffMs <= 0L ? initialBackoffMs : maxBackoffMs;
      on = on == null ? List.of() : List.copyOf(on);
    }

    static Retry defaults() {
      return new Retry(1, 0L, 1.0, 0L, List.of());
    }
  }

  public record Extract(
      String fromJsonPointer,
      String fromHeader,
      boolean fromStatus,
      String to,
      boolean required
  ) {

    public Extract {
      fromJsonPointer = normalise(fromJsonPointer);
      fromHeader = normalise(fromHeader);
      to = normalise(to);
    }
  }

  public record SetValue(
      String to,
      String template,
      boolean parseJson
  ) {

    public SetValue {
      to = normalise(to);
      template = template == null ? "" : template;
    }
  }

  public enum DebugCaptureMode {
    NONE,
    ERROR_ONLY,
    SAMPLE,
    ALWAYS
  }

  public record DebugCapture(
      DebugCaptureMode mode,
      double samplePct,
      int maxBodyBytes,
      int maxJourneyBytes,
      boolean includeHeaders,
      boolean includeRequest,
      int bodyPreviewBytes,
      int redisTtlSeconds
  ) {

    public DebugCapture {
      mode = mode == null ? DebugCaptureMode.ERROR_ONLY : mode;
      samplePct = clamp01(samplePct);
      maxBodyBytes = maxBodyBytes <= 0 ? 256 * 1024 : maxBodyBytes;
      maxJourneyBytes = maxJourneyBytes <= 0 ? 1024 * 1024 : maxJourneyBytes;
      bodyPreviewBytes = bodyPreviewBytes < 0 ? 0 : bodyPreviewBytes;
      redisTtlSeconds = redisTtlSeconds <= 0 ? 120 : redisTtlSeconds;
    }

    static DebugCapture defaults() {
      return new DebugCapture(DebugCaptureMode.ERROR_ONLY, 0.0, 256 * 1024, 1024 * 1024, true, false, 4096, 120);
    }
  }

  private static double clamp01(double value) {
    if (Double.isNaN(value)) {
      return 0.0;
    }
    return Math.max(0.0, Math.min(1.0, value));
  }

  private static String normalise(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String normaliseOrDefault(String value, String defaultValue) {
    String trimmed = normalise(value);
    return trimmed == null ? defaultValue : trimmed;
  }
}
