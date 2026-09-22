package io.pockethive.acceptance.api;

import static org.junit.jupiter.api.Assertions.*;
import io.pockethive.acceptance.support.ScriptedIngress;
import io.pockethive.auth.contract.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthApiTest {
  @Test void readsCanonicalProfileAndPreservesDeniedStatus() throws Exception {
    var profile = new AuthenticatedUserDto(UUID.randomUUID(), "viewer", "Viewer", true, AuthProvider.DEV,
        List.of(new AuthGrantDto(AuthProduct.POCKETHIVE, PocketHivePermissionIds.VIEW,
            PocketHiveResourceTypes.DEPLOYMENT, PocketHiveResourceSelectors.GLOBAL)));
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(1))) {
      ingress.reply("GET", "/auth-service/api/auth/me", 200, profile)
          .reply("GET", "/auth-service/api/auth/me", 401, Map.of());
      var api = new AuthApi(http);
      assertEquals(profile, api.profile("viewer-token"));
      assertEquals(401, assertThrows(ApiException.class, () -> api.profile("expired-token")).response().status());
    }
  }
}
