package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.*;

import io.pockethive.acceptance.api.ApiException;
import io.pockethive.acceptance.config.ApiTarget;
import io.pockethive.acceptance.support.ScriptedIngress;
import io.pockethive.auth.contract.AuthenticatedUserDto;
import io.pockethive.auth.contract.AuthProvider;
import io.pockethive.auth.contract.SessionResponseDto;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApiRunTest {
  @TempDir Path reports;

  private ApiTarget target(ScriptedIngress ingress) {
    return new ApiTarget(ingress.origin(), "test-actor", Duration.ofSeconds(1), reports);
  }

  private void login(ScriptedIngress ingress) {
    ingress.replyWith("POST", "/auth-service/api/auth/dev/login", 200, request -> {
      assertEquals("test-actor", request.required("username").textValue());
      return new SessionResponseDto("test-token", "Bearer", Instant.now().plusSeconds(60),
          new AuthenticatedUserDto(UUID.randomUUID(), "test-actor", "Test actor", true, AuthProvider.DEV, List.of()));
    });
  }

  @Test void authenticatesWithoutFetchingFixturesOrRecordingCredentials() throws Exception {
    try (var ingress = new ScriptedIngress()) {
      login(ingress);
      try (var api = ApiRun.open(target(ingress), "session")) {
        assertEquals("test-token", api.token);
        try (var artifacts = Files.list(api.evidence.directory())) {
          assertEquals(0, artifacts.count(), "Session credentials must not be recorded as evidence");
        }
      }
    }
  }

  @Test void rejectedAuthenticationFailsWithoutContinuingSetup() throws Exception {
    try (var ingress = new ScriptedIngress()) {
      ingress.reply("POST", "/auth-service/api/auth/dev/login", 401, Map.of());
      var failure = assertThrows(ApiException.class, () -> ApiRun.open(target(ingress), "denied"));
      assertEquals(401, failure.response().status());
    }
  }

  @Test void reportFailurePreservesTestFailureAndStillClosesHttp() throws Exception {
    try (var ingress = new ScriptedIngress()) {
      login(ingress);
      var api = ApiRun.open(target(ingress), "report-failure");
      var primary = new AssertionError("test failed");
      var observed = assertThrows(AssertionError.class, () -> {
        try (api) {
          Files.createDirectory(api.evidence.directory().resolve("probe.json"));
          api.evidence.record("probe", Map.of("result", "observed"));
          throw primary;
        }
      });
      assertSame(primary, observed);
      assertEquals(1, observed.getSuppressed().length);
      assertInstanceOf(IOException.class, observed.getSuppressed()[0]);
      assertThrows(IOException.class, () -> api.http.request("GET", "/must-not-be-sent", null, api.token));
    }
  }
}
