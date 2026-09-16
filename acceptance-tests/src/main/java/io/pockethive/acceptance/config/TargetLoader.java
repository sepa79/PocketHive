package io.pockethive.acceptance.config;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/**
 * Responsibility: resolve an explicitly selected lifecycle, scenario or viewer target file.
 * Must not: read legacy configuration or infer missing values.
 * Contract: RESP-ACCEPTANCE-TARGET — docs/architecture/acceptance-tests.md#resp-acceptance-target.
 */
public final class TargetLoader {
  private static final Set<String> COMMON_KEYS = Set.of(
      "ingress", "username", "requestTimeout", "evidenceDirectory");
  private static final Set<String> LIFECYCLE_KEYS = Set.of(
      "operationTimeout", "captureTimeout", "pollInterval", "templateId", "sutId",
      "captureRole", "captureDirection", "captureIoName", "sampleCount", "tapTtlSeconds", "expectedResponse");

  private TargetLoader() {}

  public static Path selectedFile() {
    String selected = System.getProperty("acceptance.target");
    if (selected == null || selected.isBlank()) throw new IllegalArgumentException("Explicit acceptance.target is required");
    return Path.of(selected);
  }

  public static AcceptanceTarget load(Path file) throws IOException {
    Path actual = file.toRealPath();
    Properties values = read(actual, LIFECYCLE_KEYS);
    ApiTarget api = api(values, actual);
    WaitLimits limits = new WaitLimits(api.requestTimeout(), duration(values, "operationTimeout"),
        duration(values, "captureTimeout"), duration(values, "pollInterval"));
    int samples = positiveInt(values, "sampleCount");
    int ttl = positiveInt(values, "tapTtlSeconds");
    if (Duration.ofSeconds(ttl).compareTo(limits.operation().plus(limits.capture())) <= 0) {
      throw new IllegalArgumentException("tapTtlSeconds must exceed operationTimeout + captureTimeout");
    }
    HttpFixture fixture = new HttpFixture(values.getProperty("templateId"), values.getProperty("sutId"),
        values.getProperty("captureRole"), values.getProperty("captureDirection"),
        values.getProperty("captureIoName"), samples, ttl, values.getProperty("expectedResponse"));
    return new AcceptanceTarget(api, limits, fixture);
  }

  public static ScenarioTarget loadScenario(Path file) throws IOException {
    Path actual = file.toRealPath();
    Properties values = read(actual, Set.of("scenarioId"));
    return new ScenarioTarget(api(values, actual), values.getProperty("scenarioId"));
  }

  public static ViewerTarget loadViewer(Path file) throws IOException {
    Path actual = file.toRealPath();
    Properties values = read(actual, Set.of("cleanupUsername", "scenarioId", "sutId", "operationTimeout", "pollInterval"));
    ApiTarget api = api(values, actual);
    if (api.username().equals(values.getProperty("cleanupUsername"))) {
      throw new IllegalArgumentException("Viewer and cleanup actors must be distinct");
    }
    return new ViewerTarget(api, values.getProperty("cleanupUsername"), values.getProperty("scenarioId"),
        values.getProperty("sutId"), new OperationLimits(api.requestTimeout(),
            duration(values, "operationTimeout"), duration(values, "pollInterval")));
  }

  private static Properties read(Path actual, Set<String> groupKeys) throws IOException {
    Set<String> keys = new HashSet<>(COMMON_KEYS);
    keys.addAll(groupKeys);
    Properties values = new Properties();
    try (var reader = Files.newBufferedReader(actual)) { values.load(reader); }
    Set<String> missing = new HashSet<>(keys);
    missing.removeAll(values.stringPropertyNames());
    Set<String> unknown = new HashSet<>(values.stringPropertyNames());
    unknown.removeAll(keys);
    if (!missing.isEmpty() || !unknown.isEmpty()) {
      throw new IllegalArgumentException("Target keys: missing=" + missing + ", unknown=" + unknown);
    }
    for (String key : keys) {
      if (values.getProperty(key).isBlank()) throw new IllegalArgumentException("Empty target setting: " + key);
    }
    return values;
  }

  private static ApiTarget api(Properties values, Path actual) {
    URI ingress = URI.create(values.getProperty("ingress"));
    if (!Set.of("http", "https").contains(ingress.getScheme()) || ingress.getHost() == null
        || ingress.getUserInfo() != null || ingress.getQuery() != null || ingress.getFragment() != null
        || !"/".equals(ingress.getPath())) {
      throw new IllegalArgumentException("ingress must be an absolute HTTP(S) origin ending with /");
    }
    return new ApiTarget(ingress, values.getProperty("username"), duration(values, "requestTimeout"),
        actual.getParent().resolve(values.getProperty("evidenceDirectory")).normalize());
  }

  private static Duration duration(Properties values, String key) {
    Duration value = Duration.parse(values.getProperty(key));
    if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(key + " must be positive");
    value.toNanos();
    return value;
  }

  private static int positiveInt(Properties values, String key) {
    int value = Integer.parseInt(values.getProperty(key));
    if (value <= 0) throw new IllegalArgumentException(key + " must be positive");
    return value;
  }
}
