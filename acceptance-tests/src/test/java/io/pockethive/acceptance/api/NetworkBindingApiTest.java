package io.pockethive.acceptance.api;

import static org.junit.jupiter.api.Assertions.*;
import io.pockethive.acceptance.support.ScriptedIngress;
import io.pockethive.swarm.model.NetworkBinding;
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
}
