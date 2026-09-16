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
 * Responsibility: resolve one explicit acceptance target file.
 * Must not: read legacy configuration or infer missing values.
 * Contract: RESP-ACCEPTANCE-TARGET — docs/architecture/acceptance-tests.md#resp-acceptance-target.
 */
public final class TargetLoader {
  private static final Set<String> KEYS = Set.of(
      "ingress", "username", "requestTimeout", "operationTimeout", "captureTimeout", "pollInterval",
      "templateId", "sutId", "captureRole", "captureDirection", "captureIoName",
      "sampleCount", "tapTtlSeconds", "expectedResponse", "evidenceDirectory");

  private TargetLoader() {}

  public static AcceptanceTarget load(Path file) throws IOException {
    Path actual = file.toRealPath();
    Properties values = new Properties();
    try (var reader = Files.newBufferedReader(actual)) { values.load(reader); }
    Set<String> missing = new HashSet<>(KEYS);
    missing.removeAll(values.stringPropertyNames());
    Set<String> unknown = new HashSet<>(values.stringPropertyNames());
    unknown.removeAll(KEYS);
    if (!missing.isEmpty() || !unknown.isEmpty()) {
      throw new IllegalArgumentException("Target keys: missing=" + missing + ", unknown=" + unknown);
    }
    for (String key : KEYS) {
      if (values.getProperty(key).isBlank()) throw new IllegalArgumentException("Empty target setting: " + key);
    }
    URI ingress = URI.create(values.getProperty("ingress"));
    if (!Set.of("http", "https").contains(ingress.getScheme()) || ingress.getHost() == null
        || ingress.getUserInfo() != null || ingress.getQuery() != null || ingress.getFragment() != null
        || !"/".equals(ingress.getPath())) {
      throw new IllegalArgumentException("ingress must be an absolute HTTP(S) origin ending with /");
    }
    WaitLimits limits = new WaitLimits(duration(values, "requestTimeout"), duration(values, "operationTimeout"),
        duration(values, "captureTimeout"), duration(values, "pollInterval"));
    int samples = positiveInt(values, "sampleCount");
    int ttl = positiveInt(values, "tapTtlSeconds");
    if (Duration.ofSeconds(ttl).compareTo(limits.operation().plus(limits.capture())) <= 0) {
      throw new IllegalArgumentException("tapTtlSeconds must exceed operationTimeout + captureTimeout");
    }
    HttpFixture fixture = new HttpFixture(values.getProperty("templateId"), values.getProperty("sutId"),
        values.getProperty("captureRole"), values.getProperty("captureDirection"),
        values.getProperty("captureIoName"), samples, ttl, values.getProperty("expectedResponse"));
    return new AcceptanceTarget(ingress, values.getProperty("username"), limits, fixture,
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
