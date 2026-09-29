package io.pockethive.acceptance.resources;

import static org.junit.jupiter.api.Assertions.*;
import io.pockethive.acceptance.api.*;
import io.pockethive.acceptance.evidence.RunEvidence;
import io.pockethive.acceptance.support.ScriptedIngress;
import io.pockethive.auth.contract.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuthUserResourceTest {
  @TempDir Path reports;
  private static final UUID ID = UUID.fromString("137ab258-2a5a-4f09-957c-b3ca177d7b0b");
  private static final String USER = "owned-user";
  private static final String USERS = "/auth-service/api/auth/admin/users";
  private static final AuthGrantDto GRANT = new AuthGrantDto(AuthProduct.POCKETHIVE, PocketHivePermissionIds.RUN,
      PocketHiveResourceTypes.BUNDLE, "acceptance/one");

  @Test void assertionFailureStillRevokesDeactivatesAndVerifiesTheOwnedUser() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(1));
         var evidence = new RunEvidence(reports, "user")) {
      creation(ingress);
      cleanup(ingress, 200, false);
      var failure = assertThrows(AssertionError.class, () -> {
        try (var user = resource(http, evidence)) {
          user.provision(List.of(GRANT));
          throw new AssertionError("test failed");
        }
      });
      assertEquals("test failed", failure.getMessage());
      assertTrue(Files.exists(evidence.directory().resolve("user-" + ID + "-deactivated.json")));
    }
  }
  @Test void preExistingIdentityIsNeverChanged() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(1));
         var evidence = new RunEvidence(reports, "existing")) {
      ingress.reply("GET", USERS, 200, List.of(profile(true, List.of(GRANT))));
      try (var user = resource(http, evidence)) {
        assertThrows(IllegalStateException.class, () -> user.provision(List.of(GRANT)));
      }
    }
  }
  @Test void lostCreateResponseIsInspectedAndTheExactCreatedUserIsCleaned() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(1));
         var evidence = new RunEvidence(reports, "partial")) {
      ingress.reply("GET", USERS, 200, List.of()).reply("PUT", USERS + "/" + ID, 500, Map.of());
      cleanup(ingress, 200, false);
      assertThrows(ApiException.class, () -> {
        try (var user = resource(http, evidence)) { user.provision(List.of(GRANT)); }
      });
    }
  }
  @Test void failedGrantAssignmentStillCleansTheCreatedUser() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(1));
         var evidence = new RunEvidence(reports, "grant-failure")) {
      ingress.reply("GET", USERS, 200, List.of())
          .reply("PUT", USERS + "/" + ID, 200, profile(true, List.of()))
          .reply("PUT", USERS + "/" + ID + "/grants", 500, Map.of());
      cleanup(ingress, 200, false);
      assertThrows(ApiException.class, () -> {
        try (var user = resource(http, evidence)) { user.provision(List.of(GRANT)); }
      });
    }
  }
  @Test void revocationFailureDoesNotSkipDeactivationAndRemainsVisible() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(1));
         var evidence = new RunEvidence(reports, "revoke-failure")) {
      creation(ingress);
      cleanup(ingress, 500, false);
      var failure = assertThrows(ApiException.class, () -> {
        try (var user = resource(http, evidence)) { user.provision(List.of(GRANT)); }
      });
      assertEquals(500, failure.response().status());
    }
  }
  @Test void successfulMutationResponsesDoNotHideAnActiveReadback() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(1));
         var evidence = new RunEvidence(reports, "active-readback")) {
      creation(ingress);
      cleanup(ingress, 200, true);
      var failure = assertThrows(IllegalStateException.class, () -> {
        try (var user = resource(http, evidence)) { user.provision(List.of(GRANT)); }
      });
      assertTrue(failure.getMessage().contains("cleanup incomplete"));
    }
  }
  @Test void evidenceFailureIsReportedAfterCleanup() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(1))) {
      creation(ingress);
      cleanup(ingress, 200, false);
      assertThrows(java.io.IOException.class, () -> {
        try (var evidence = new RunEvidence(reports, "bad-evidence"); var user = resource(http, evidence)) {
          Files.delete(evidence.directory());
          Files.createFile(evidence.directory());
          user.provision(List.of(GRANT));
        }
      });
    }
  }
  @Test void unconfirmedAcquisitionNeverMutatesAMismatchedIdentity() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(1));
         var evidence = new RunEvidence(reports, "wrong-owner")) {
      ingress.reply("GET", USERS, 200, List.of()).reply("PUT", USERS + "/" + ID, 500, Map.of())
          .reply("GET", USERS, 200, List.of(new AuthenticatedUserDto(ID, "someone-else", "Someone", true,
              AuthProvider.DEV, List.of(GRANT))));
      var failure = assertThrows(ApiException.class, () -> {
        try (var user = resource(http, evidence)) { user.provision(List.of(GRANT)); }
      });
      assertEquals(1, failure.getSuppressed().length);
      assertTrue(failure.getSuppressed()[0].getMessage().contains("identity mismatch"));
    }
  }
  @Test void absentReadbackDoesNotResolveUnconfirmedUserCreation() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(1));
         var evidence = new RunEvidence(reports, "no-user")) {
      ingress.reply("GET", USERS, 200, List.of()).reply("PUT", USERS + "/" + ID, 500, Map.of())
          .reply("GET", USERS, 200, List.of());
      var failure = assertThrows(ApiException.class, () -> {
        try (var user = resource(http, evidence)) { user.provision(List.of(GRANT)); }
      });
      assertEquals(1, failure.getSuppressed().length);
      assertTrue(failure.getSuppressed()[0].getMessage().contains("Unconfirmed user creation"));
      assertFalse(Files.exists(evidence.directory().resolve("user-" + ID + "-absent.json")));
    }
  }

  private static AuthUserResource resource(PocketHiveHttp http, RunEvidence evidence) {
    return new AuthUserResource(ID, USER, new AuthAdminApi(http, "admin-token"), new AuthApi(http), evidence);
  }
  private static AuthenticatedUserDto profile(boolean active, List<AuthGrantDto> grants) {
    return new AuthenticatedUserDto(ID, USER, USER, active, AuthProvider.DEV, grants);
  }
  private static void creation(ScriptedIngress ingress) {
    ingress.reply("GET", USERS, 200, List.of())
        .replyWith("PUT", USERS + "/" + ID, 200, body -> {
          assertEquals(USER, body.required("username").textValue());
          assertTrue(body.required("active").booleanValue());
          return profile(true, List.of());
        })
        .replyWith("PUT", USERS + "/" + ID + "/grants", 200, body -> {
          assertEquals(1, body.required("grants").size());
          assertEquals(GRANT.resourceSelector(), body.required("grants").get(0).required("resourceSelector").textValue());
          return profile(true, List.of(GRANT));
        });
  }
  private static void cleanup(ScriptedIngress ingress, int revokeStatus, boolean activeReadback) {
    ingress.reply("GET", USERS, 200, List.of(profile(true, List.of(GRANT))))
        .replyWith("PUT", USERS + "/" + ID + "/grants", revokeStatus, body -> {
          assertTrue(body.required("grants").isEmpty()); return profile(true, List.of());
        })
        .replyWith("PUT", USERS + "/" + ID, 200, body -> {
          assertFalse(body.required("active").booleanValue()); return profile(false, List.of());
        })
        .reply("GET", USERS, 200, List.of(profile(activeReadback, List.of())));
    if (!activeReadback) ingress.replyWith("POST", "/auth-service/api/auth/dev/login", 401, body -> {
      assertEquals(USER, body.required("username").textValue()); return Map.of();
    });
  }
}
