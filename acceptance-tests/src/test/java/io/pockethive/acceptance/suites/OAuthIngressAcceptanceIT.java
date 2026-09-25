package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pockethive.acceptance.api.ApiSurface;
import io.pockethive.acceptance.api.PocketHiveHttp;
import io.pockethive.acceptance.config.TargetLoader;
import io.pockethive.acceptance.evidence.RunEvidence;
import io.pockethive.auth.contract.PocketHiveMcpScopes;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Responsibility: verify public OAuth login redirects preserve ingress identity despite forwarded-header injection.
 * Must not: log cookies or page tokens, follow unverified redirects, authenticate users or call backend ports.
 * Contract: docs/architecture/acceptance-tests.md#oauth-ingress-redirect-acceptance.
 */
@Tag("oauth-ingress")
class OAuthIngressAcceptanceIT {
  @ParameterizedTest(name = "{0}: login stays on the selected public origin")
  @MethodSource("forwardingHeaders")
  void preservesPublicLoginOrigin(String name, Map<String, String> headers) throws Exception {
    var target = TargetLoader.loadApi(TargetLoader.selectedFile());
    try (var evidence = new RunEvidence(target.evidenceDirectory(), "oauth-ingress-" + name);
         var http = new PocketHiveHttp(target.ingress(), target.requestTimeout())) {
      System.out.println("Acceptance evidence: " + evidence.directory());
      var response = http.getWithHeaders(authorizationPath(target.ingress()), "text/html", headers);
      evidence.record("authorization-redirect", Map.of(
          "case", name, "status", response.status(),
          "location", response.location() == null ? "" : response.location()));
      response.expect(302);
      assertNotNull(response.location(), "Authorization must provide a login Location");
      URI expected = target.ingress().resolve(ApiSurface.AUTH.publicPath("/oauth/dev/login"));
      assertEquals(withoutDefaultPort(expected), withoutDefaultPort(URI.create(response.location())),
          "Login redirect must retain public scheme, authority and prefix");
      var login = http.getWithHeaders(expected.getRawPath(), "text/html", headers);
      evidence.record("login-page", Map.of("case", name, "status", login.status()));
      login.expect(200);
      assertTrue(login.body().contains("<form"), "The public login endpoint must serve its login form");
    }
  }

  static Stream<Arguments> forwardingHeaders() {
    return Stream.of(
        Arguments.of("ordinary", Map.of()),
        Arguments.of("forwarded", Map.of("Forwarded", "host=untrusted.invalid:65534;proto=https")),
        Arguments.of("port", Map.of("X-Forwarded-Port", "65534")),
        Arguments.of("host", Map.of("X-Forwarded-Host", "untrusted.invalid:65534")),
        Arguments.of("http", Map.of("X-Forwarded-Proto", "http")),
        Arguments.of("https", Map.of("X-Forwarded-Proto", "https")),
        Arguments.of("prefix", Map.of("X-Forwarded-Prefix", "/untrusted")),
        Arguments.of("combined", Map.of(
            "Forwarded", "host=untrusted.invalid:65534;proto=https",
            "X-Forwarded-Port", "65534", "X-Forwarded-Host", "untrusted.invalid:65534",
            "X-Forwarded-Proto", "https", "X-Forwarded-Prefix", "/untrusted")));
  }

  private static URI withoutDefaultPort(URI uri) throws URISyntaxException {
    if (("http".equals(uri.getScheme()) && uri.getPort() == 80)
        || ("https".equals(uri.getScheme()) && uri.getPort() == 443)) {
      return new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), -1,
          uri.getPath(), uri.getQuery(), uri.getFragment());
    }
    return uri;
  }

  private static String authorizationPath(URI ingress) throws Exception {
    String verifier = "oauth-ingress-test-verifier-with-at-least-forty-three-characters";
    String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    var parameters = Map.of(
        "response_type", "code", "client_id", "pockethive-vscode",
        "redirect_uri", "http://127.0.0.1:38125/callback", "resource", ingress.resolve("/mcp").toString(),
        "scope", PocketHiveMcpScopes.DISCOVER, "state", "oauth-ingress-pre-authentication",
        "code_challenge", challenge, "code_challenge_method", "S256");
    String query = parameters.entrySet().stream().map(entry -> entry.getKey() + "="
        + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
    return ApiSurface.AUTH.publicPath("/oauth/authorize") + "?" + query;
  }
}
