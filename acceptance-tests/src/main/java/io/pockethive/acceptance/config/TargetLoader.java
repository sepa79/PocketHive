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
 * Responsibility: resolve an explicitly selected acceptance target file.
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

  public static ExportTarget loadExport(Path file) throws IOException {
    Path actual = file.toRealPath();
    Set<String> keys = new HashSet<>(LIFECYCLE_KEYS);
    keys.addAll(Set.of("redisConnectionId", "runtimeRoot"));
    Properties values = read(actual, keys);
    Path root = Path.of(values.getProperty("runtimeRoot"));
    if (!root.isAbsolute()) throw new IllegalArgumentException("runtimeRoot must be absolute");
    root = root.toRealPath();
    if (!Files.isDirectory(root) || !Files.isReadable(root)) {
      throw new IllegalArgumentException("runtimeRoot must be an existing readable directory");
    }
    return new ExportTarget(lifecycle(values, actual), values.getProperty("redisConnectionId"), root);
  }

  public static WebAuthTarget loadWebAuth(Path file) throws IOException {
    Path actual = file.toRealPath();
    Set<String> keys = new HashSet<>(LIFECYCLE_KEYS);
    keys.addAll(Set.of("redisConnectionId", "mockUsername", "mockPassword"));
    Properties values = read(actual, keys);
    return new WebAuthTarget(lifecycle(values, actual), values.getProperty("redisConnectionId"),
        values.getProperty("mockUsername"), values.getProperty("mockPassword"));
  }

  public static TxOutcomeTarget loadTxOutcome(Path file) throws IOException {
    Path actual = file.toRealPath();
    Set<String> keys = new HashSet<>(LIFECYCLE_KEYS);
    keys.addAll(Set.of("grafanaUsername", "grafanaPassword", "grafanaDatasourceUid", "outcomeTable"));
    Properties values = read(actual, keys);
    return new TxOutcomeTarget(lifecycle(values, actual), values.getProperty("grafanaUsername"),
        values.getProperty("grafanaPassword"), values.getProperty("grafanaDatasourceUid"), values.getProperty("outcomeTable"));
  }

  public static RedisDataTarget loadRedisData(Path file) throws IOException {
    Path actual = file.toRealPath();
    Set<String> keys = new HashSet<>(LIFECYCLE_KEYS);
    keys.add("redisConnectionId");
    Properties values = read(actual, keys);
    return new RedisDataTarget(lifecycle(values, actual), values.getProperty("redisConnectionId"));
  }

  public static RedisFixtureTarget loadRedisFixture(Path file) throws IOException {
    Path actual = file.toRealPath();
    Properties values = read(actual, Set.of("redisConnectionId"));
    return new RedisFixtureTarget(api(values, actual), values.getProperty("redisConnectionId"));
  }

  public static FreshDeploymentTarget loadFreshDeployment(Path file) throws IOException {
    Path actual = file.toRealPath();
    Properties values = read(actual, Set.of("deploymentId"));
    return new FreshDeploymentTarget(api(values, actual), values.getProperty("deploymentId"));
  }

  public static ApiTarget loadApi(Path file) throws IOException {
    Path actual = file.toRealPath();
    return api(read(actual, Set.of()), actual);
  }

  public static AcceptanceTarget load(Path file) throws IOException {
    Path actual = file.toRealPath();
    return lifecycle(read(actual, LIFECYCLE_KEYS), actual);
  }

  public static ProxyTarget loadProxy(Path file) throws IOException {
    Path actual = file.toRealPath();
    return proxy(read(actual, proxyKeys()), actual);
  }

  public static BindingRecoveryTarget loadBindingRecovery(Path file) throws IOException {
    Path actual = file.toRealPath();
    Set<String> keys = proxyKeys();
    keys.add("minimumRejectionDuration");
    Properties values = read(actual, keys);
    var proxy = proxy(values, actual);
    var minimum = duration(values, "minimumRejectionDuration");
    if (minimum.compareTo(proxy.lifecycle().api().requestTimeout()) >= 0) {
      throw new IllegalArgumentException("requestTimeout must exceed minimumRejectionDuration");
    }
    return new BindingRecoveryTarget(proxy, minimum);
  }

  private static Set<String> proxyKeys() {
    Set<String> keys = new HashSet<>(LIFECYCLE_KEYS);
    keys.addAll(Set.of("networkProfileId", "endpointId"));
    return keys;
  }

  private static ProxyTarget proxy(Properties values, Path actual) {
    return new ProxyTarget(lifecycle(values, actual), values.getProperty("networkProfileId"),
        values.getProperty("endpointId"));
  }

  public static TcpTimeoutTarget loadTcpTimeout(Path file) throws IOException {
    Path actual = file.toRealPath();
    Set<String> keys = new HashSet<>(LIFECYCLE_KEYS);
    keys.addAll(Set.of("mappingId", "mockUsername", "mockPassword", "quietWindow"));
    Properties values = read(actual, keys);
    var lifecycle = lifecycle(values, actual);
    var quiet = duration(values, "quietWindow");
    var limits = lifecycle.limits();
    if (Duration.ofSeconds(lifecycle.fixture().tapTtlSeconds()).compareTo(
        limits.operation().plus(limits.capture()).plus(quiet).plus(limits.request())) <= 0) {
      throw new IllegalArgumentException("tapTtlSeconds must cover START + error wait + quiet window + final read");
    }
    return new TcpTimeoutTarget(lifecycle, values.getProperty("mappingId"), values.getProperty("mockUsername"),
        values.getProperty("mockPassword"), quiet);
  }

  private static AcceptanceTarget lifecycle(Properties values, Path actual) {
    ApiTarget api = api(values, actual);
    WaitLimits limits = new WaitLimits(api.requestTimeout(), duration(values, "operationTimeout"),
        duration(values, "captureTimeout"), duration(values, "pollInterval"));
    int samples = positiveInt(values, "sampleCount");
    int ttl = positiveInt(values, "tapTtlSeconds");
    if (Duration.ofSeconds(ttl).compareTo(limits.operation().plus(limits.capture())) <= 0) {
      throw new IllegalArgumentException("tapTtlSeconds must exceed operationTimeout + captureTimeout");
    }
    WorkFixture fixture = new WorkFixture(values.getProperty("templateId"), values.getProperty("sutId"),
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
    requireDistinctActors(api, values);
    return new ViewerTarget(api, values.getProperty("cleanupUsername"), values.getProperty("scenarioId"),
        values.getProperty("sutId"), operations(api, values));
  }

  public static RunnerTarget loadRunner(Path file) throws IOException {
    Path actual = file.toRealPath();
    Properties values = read(actual, Set.of("cleanupUsername", "folder", "scenarioId", "deniedScenarioId",
        "sutId", "operationTimeout", "pollInterval"));
    ApiTarget api = api(values, actual);
    requireDistinctActors(api, values);
    if (values.getProperty("scenarioId").equals(values.getProperty("deniedScenarioId"))) {
      throw new IllegalArgumentException("Allowed and denied scenarios must differ");
    }
    return new RunnerTarget(api, values.getProperty("cleanupUsername"), values.getProperty("folder"),
        values.getProperty("scenarioId"), values.getProperty("deniedScenarioId"), values.getProperty("sutId"),
        operations(api, values));
  }

  private static final Set<String> AUTH_KEYS = Set.of("folder", "bundle", "scenarioId", "siblingScenarioId", "outsideScenarioId",
      "sutId", "operationTimeout", "pollInterval");

  public static ProvisionedAuthTarget loadProvisionedAuth(Path file) throws IOException {
    Path actual = file.toRealPath();
    return provisionedAuth(read(actual, AUTH_KEYS), actual);
  }
  public static SwarmAuthorizationTarget loadSwarmAuthorization(Path file) throws IOException {
    Path actual = file.toRealPath();
    Set<String> keys = new HashSet<>(AUTH_KEYS);
    keys.addAll(Set.of("captureRole", "captureDirection", "captureIoName", "sampleCount", "tapTtlSeconds"));
    Properties values = read(actual, keys);
    var auth = provisionedAuth(values, actual);
    int ttl = positiveInt(values, "tapTtlSeconds");
    if (Duration.ofSeconds(ttl).compareTo(auth.limits().request().multipliedBy(8)) <= 0) {
      throw new IllegalArgumentException("tapTtlSeconds must cover eight authorization request budgets");
    }
    return new SwarmAuthorizationTarget(auth, new io.pockethive.acceptance.capture.TapSelection(
        values.getProperty("captureRole"), values.getProperty("captureDirection"), values.getProperty("captureIoName"),
        positiveInt(values, "sampleCount"), ttl));
  }
  private static ProvisionedAuthTarget provisionedAuth(Properties values, Path actual) {
    ApiTarget api = api(values, actual);
    if (new HashSet<>(java.util.List.of(values.getProperty("scenarioId"), values.getProperty("siblingScenarioId"),
        values.getProperty("outsideScenarioId"))).size() != 3) {
      throw new IllegalArgumentException("Allowed, sibling and outside scenarios must be distinct");
    }
    return new ProvisionedAuthTarget(api, values.getProperty("folder"), values.getProperty("bundle"),
        values.getProperty("scenarioId"), values.getProperty("siblingScenarioId"), values.getProperty("outsideScenarioId"),
        values.getProperty("sutId"), operations(api, values));
  }

  public static NetworkAccessTarget loadNetworkAccess(Path file) throws IOException {
    Path actual = file.toRealPath();
    Properties values = read(actual, Set.of("runnerUsername", "runnerFolder"));
    ApiTarget api = api(values, actual);
    if (api.username().equals(values.getProperty("runnerUsername"))) {
      throw new IllegalArgumentException("Viewer and runner actors must be distinct");
    }
    return new NetworkAccessTarget(api, values.getProperty("runnerUsername"), values.getProperty("runnerFolder"));
  }

  private static OperationLimits operations(ApiTarget api, Properties values) {
    return new OperationLimits(api.requestTimeout(), duration(values, "operationTimeout"), duration(values, "pollInterval"));
  }

  private static void requireDistinctActors(ApiTarget api, Properties values) {
    if (api.username().equals(values.getProperty("cleanupUsername"))) {
      throw new IllegalArgumentException("Requesting and cleanup actors must be distinct");
    }
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
