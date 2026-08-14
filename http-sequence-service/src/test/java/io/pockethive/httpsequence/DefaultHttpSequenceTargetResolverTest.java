package io.pockethive.httpsequence;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultHttpSequenceTargetResolverTest {

  private final DefaultHttpSequenceTargetResolver resolver = new DefaultHttpSequenceTargetResolver();

  @Test
  void resolvesWorkerSutAndLiteralBasesWithoutFallback() {
    HttpSequenceWorkerConfig config = config(
        List.of(step(null, null), step("accounts", null), step(null, "http://audit:9080/audit")),
        sut(Map.of("accounts", endpoint("HTTPS", "https://accounts:10443/api"))));

    List<HttpSequenceTargetResolver.BaseTarget> targets = resolver.resolveBases(config);

    assertThat(targets)
        .extracting(HttpSequenceTargetResolver.BaseTarget::baseUri)
        .containsExactly(
            URI.create("http://worker:8080/root"),
            URI.create("https://accounts:10443/api"),
            URI.create("http://audit:9080/audit"));
    assertThat(targets)
        .extracting(HttpSequenceTargetResolver.BaseTarget::source)
        .containsExactly(
            HttpSequenceTargetResolver.TargetSource.WORKER_BASE_URL,
            HttpSequenceTargetResolver.TargetSource.SUT_ENDPOINT,
            HttpSequenceTargetResolver.TargetSource.STEP_BASE_URL);
    assertThat(targets.get(1).sutEndpointId()).isEqualTo("accounts");
  }

  @ParameterizedTest
  @MethodSource("pathExamples")
  void joinsRenderedPathsWithoutDroppingTheBasePath(String renderedPath, String expected) {
    HttpSequenceTargetResolver.BaseTarget base = new HttpSequenceTargetResolver.BaseTarget(
        URI.create("https://accounts:10443/customer-api"),
        HttpSequenceTargetResolver.TargetSource.SUT_ENDPOINT,
        "accounts");

    HttpSequenceTargetResolver.ResolvedTarget target = resolver.resolve(base, renderedPath);

    assertThat(target.uri()).isEqualTo(URI.create(expected));
    assertThat(target.source()).isEqualTo(HttpSequenceTargetResolver.TargetSource.SUT_ENDPOINT);
    assertThat(target.sutEndpointId()).isEqualTo("accounts");
  }

  @Test
  void acceptsTheHighestValidTcpPort() {
    HttpSequenceWorkerConfig config = config(
        List.of(step(null, "http://audit.example.test:65535/root")), Map.of());

    assertThat(resolver.resolveBases(config).getFirst().baseUri())
        .isEqualTo(URI.create("http://audit.example.test:65535/root"));
  }

  static Stream<Arguments> pathExamples() {
    return Stream.of(
        Arguments.of("/customers/42?view=full", "https://accounts:10443/customer-api/customers/42?view=full"),
        Arguments.of("customers", "https://accounts:10443/customer-api/customers"),
        Arguments.of("", "https://accounts:10443/customer-api"),
        Arguments.of("?view=full", "https://accounts:10443/customer-api?view=full"),
        Arguments.of("/", "https://accounts:10443/customer-api/"));
  }

  @Test
  void rejectsSutOverrideWithoutEnrichedContext() {
    HttpSequenceWorkerConfig config = config(List.of(step("accounts", null)), Map.of());

    assertThatThrownBy(() -> resolver.resolveBases(config))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("steps[0].sutEndpointId requires an enriched selected-SUT context");
  }

  @Test
  void rejectsUnknownSutEndpoint() {
    HttpSequenceWorkerConfig config = config(List.of(step("missing", null)), sut(Map.of()));

    assertThatThrownBy(() -> resolver.resolveBases(config))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("steps[0].sutEndpointId references unknown SUT endpoint 'missing'");
  }

  @Test
  void rejectsNonHttpSutEndpoint() {
    HttpSequenceWorkerConfig config = config(
        List.of(step("ledger", null)), sut(Map.of("ledger", endpoint("TCP", "ledger:9000"))));

    assertThatThrownBy(() -> resolver.resolveBases(config))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("steps[0].sutEndpointId must reference an HTTP or HTTPS endpoint");
  }

  @Test
  void rejectsBlankSutEndpointBaseUrlAsMissingContractData() {
    HttpSequenceWorkerConfig config = config(
        List.of(step("accounts", null)), sut(Map.of("accounts", endpoint("HTTP", "  "))));

    assertThatThrownBy(() -> resolver.resolveBases(config))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("steps[0].sutEndpointId endpoint baseUrl is required");
  }

  @Test
  void rejectsNonStringSutEndpointBaseUrlAsMissingContractData() {
    HttpSequenceWorkerConfig config = config(
        List.of(step("accounts", null)),
        sut(Map.of("accounts", Map.of("kind", "HTTP", "baseUrl", 8080))));

    assertThatThrownBy(() -> resolver.resolveBases(config))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("steps[0].sutEndpointId endpoint baseUrl is required");
  }

  @Test
  void rejectsSutEndpointKindThatDoesNotMatchItsBaseUrlScheme() {
    HttpSequenceWorkerConfig config = config(
        List.of(step("accounts", null)),
        sut(Map.of("accounts", endpoint("HTTPS", "http://accounts:8080"))));

    assertThatThrownBy(() -> resolver.resolveBases(config))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("steps[0].sutEndpointId endpoint kind HTTPS does not match baseUrl scheme http");
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "relative/path",
      "ftp://files.example.test/root",
      "http://user:password@example.test/root",
      "http://example.test/root?query=yes",
      "http://example.test/root#fragment",
      "http://example.test/root/../admin",
      "http://example.test/root/%2e%2e/admin",
      "http://example.test/root/%252e%252e/admin",
      "http://example.test/root/%2e%2e%2fadmin",
      "http://example.test/root/%25%32%65%25%32%65%25%32%66admin",
      "http://example.test:65536/root",
      "{{ sut.endpoints['accounts'].baseUrl }}"
  })
  void rejectsInvalidLiteralBaseUris(String baseUrl) {
    HttpSequenceWorkerConfig config = config(List.of(step(null, baseUrl)), Map.of());

    assertThatThrownBy(() -> resolver.resolveBases(config))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("steps[0].baseUrl");
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "https://other.example.test/path",
      "//other.example.test/path",
      "/safe/../admin",
      "/safe/%2e%2e/admin",
      "/safe/%252E%252e/admin",
      "/safe/%2e%2e%2fadmin",
      "/safe/%2e%2e%5cadmin",
      "/safe/%25%32%65%25%32%65%25%32%66admin",
      "/path#fragment"
  })
  void rejectsRenderedPathsThatCanChangeOrEscapeTheAuthorityOrBase(String renderedPath) {
    HttpSequenceTargetResolver.BaseTarget base = new HttpSequenceTargetResolver.BaseTarget(
        URI.create("http://worker:8080/root"),
        HttpSequenceTargetResolver.TargetSource.WORKER_BASE_URL,
        null);

    assertThatThrownBy(() -> resolver.resolve(base, renderedPath))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Rendered HTTP path");
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "/safe/report%2Etxt",
      "/safe/folder%2Ffile",
      "/safe/%252efile",
      "/safe/%2e%2e%2e",
      "/safe/%252",
      "/safe/price%24value",
      "/safe/backslash%5Cfile"
  })
  void preservesSafeEncodedPathContent(String renderedPath) {
    HttpSequenceTargetResolver.BaseTarget base = new HttpSequenceTargetResolver.BaseTarget(
        URI.create("http://worker:8080/root"),
        HttpSequenceTargetResolver.TargetSource.WORKER_BASE_URL,
        null);

    HttpSequenceTargetResolver.ResolvedTarget target = resolver.resolve(base, renderedPath);

    assertThat(target.uri()).isEqualTo(URI.create("http://worker:8080/root" + renderedPath));
  }

  private static HttpSequenceWorkerConfig config(
      List<HttpSequenceWorkerConfig.Step> steps,
      Map<String, Object> privateConfig) {
    return new HttpSequenceWorkerConfig(
        "http://worker:8080/root", "/templates", "customers", 1, steps,
        HttpSequenceWorkerConfig.DebugCapture.defaults(), Map.of(), privateConfig);
  }

  private static HttpSequenceWorkerConfig.Step step(String sutEndpointId, String baseUrl) {
    return new HttpSequenceWorkerConfig.Step(
        "one", "call", null, false, null, List.of(), List.of(), sutEndpointId, baseUrl);
  }

  private static Map<String, Object> sut(Map<String, Object> endpoints) {
    return Map.of("authProfile", Map.of("sut", Map.of("id", "sut-1", "endpoints", endpoints)));
  }

  private static Map<String, Object> endpoint(String kind, String baseUrl) {
    return Map.of("kind", kind, "baseUrl", baseUrl);
  }
}
