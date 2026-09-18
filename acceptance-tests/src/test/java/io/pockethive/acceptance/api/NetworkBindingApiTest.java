package io.pockethive.acceptance.api;

import static org.junit.jupiter.api.Assertions.*;
import io.pockethive.acceptance.support.ScriptedIngress;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.pockethive.swarm.model.NetworkBinding;
import io.pockethive.swarm.model.NetworkBindingRequest;
import io.pockethive.swarm.model.NetworkBindingClearRequest;
import io.pockethive.swarm.model.ResolvedSutEnvironment;
import io.pockethive.swarm.model.NetworkMode;
import io.pockethive.swarm.model.ResolvedSutEndpoint;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NetworkBindingApiTest {
  @Test void readsCanonicalBindingAndChecks404AtTheSameEncodedIngressPath() throws Exception {
    String path = "/network-proxy-manager/api/network/bindings/swarm%2Fopaque";
    var binding = new NetworkBinding("swarm/opaque", "sut", NetworkMode.PROXIED, "selected",
        NetworkMode.PROXIED, "actor", Instant.parse("2026-09-17T00:00:00Z"),
        List.of(new ResolvedSutEndpoint("default", "http", "http://proxy:18090", "proxy:18090", "sut:8080")));
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(1))) {
      ingress.reply("GET", path, 200, binding).reply("GET", path, 404, Map.of());
      var api = new NetworkBindingApi(http, "");
      assertEquals(binding, api.requireBinding("swarm/opaque"));
      assertEquals(404, api.requireAbsent("swarm/opaque").status());
    }
  }

  @ParameterizedTest @ValueSource(ints = {200, 401, 500})
  void doesNotTreatExistingBindingOrReadFailureAsAbsence(int status) throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(1))) {
      ingress.reply("GET", "/network-proxy-manager/api/network/bindings/owned", status, Map.of());
      assertThrows(ApiException.class, () -> new NetworkBindingApi(http, "").requireAbsent("owned"));
    }
  }

  @ParameterizedTest @ValueSource(ints = {200, 500})
  void sendsCanonicalCandidateUnchangedAndPreservesResponse(int status) throws Exception {
    var endpoint = new ResolvedSutEndpoint("selected", "http", "http://proxy:18090", "invalid:-1", "sut:8080");
    var candidate = new NetworkBindingRequest("owned-sut", NetworkMode.PROXIED, "selected-profile", "tester",
        "candidate probe", new ResolvedSutEnvironment("owned-sut", "Owned SUT", "http", Map.of("selected", endpoint)));
    var expected = JsonMapper.builder().findAndAddModules().build().valueToTree(candidate);
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(1))) {
      ingress.replyWith("POST", "/network-proxy-manager/api/network/bindings/owned%20id", status, body -> {
        assertEquals(expected, body, "The API adapter must not normalize or strip deliberately invalid candidate data");
        return Map.of("detail", "original response");
      });
      var response = new NetworkBindingApi(http, "token").bind("owned id", candidate);
      assertEquals(status, response.status());
      assertEquals("original response", http.tree(response).required("detail").textValue());
    }
  }

  @ParameterizedTest @ValueSource(ints = {200, 403})
  void clearUsesTheExactOwnedIdentityAndDoesNotHideDenial(int status) throws Exception {
    var request = new NetworkBindingClearRequest("owned-sut", "tester", "explicit clear");
    var expected = JsonMapper.builder().findAndAddModules().build().valueToTree(request);
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(1))) {
      ingress.replyWith("POST", "/network-proxy-manager/api/network/bindings/owned%20id/clear", status, body -> {
        assertEquals(expected, body);
        return Map.of("detail", "clear response");
      });
      var response = new NetworkBindingApi(http, "token").clear("owned id", request);
      assertEquals(status, response.status());
      assertEquals("clear response", http.tree(response).required("detail").textValue());
    }
  }
}
