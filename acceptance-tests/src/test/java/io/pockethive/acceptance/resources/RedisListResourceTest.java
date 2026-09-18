package io.pockethive.acceptance.resources;

import static org.junit.jupiter.api.Assertions.*;
import io.pockethive.acceptance.api.*;
import io.pockethive.acceptance.evidence.RunEvidence;
import io.pockethive.acceptance.support.ScriptedIngress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RedisListResourceTest {
  @TempDir Path reports;
  private static String path(String key) { return "/redis/apiv2/key/R%3Aredis%3A6379%3A0/" + key; }
  private static Map<String, Object> absent(String key) { return Map.of("key", key, "type", "none"); }
  private static Map<String, Object> present(String key) { return Map.of("key", key, "type", "list", "length", 1); }
  private static RedisListResource resource(PocketHiveHttp http, RunEvidence evidence) {
    return new RedisListResource(new RedisCommanderApi(http, "R:redis:6379:0"), evidence);
  }
  private static void seedReplies(ScriptedIngress ingress, String key) {
    ingress.reply("GET", path(key), 200, absent(key))
        .replyText("POST", path(key), 200, "ok").reply("GET", path(key), 200, present(key));
  }
  @Test void failureCleansOnlyOwnedKeyAndVerifiesAbsence() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(1));
         var evidence = new RunEvidence(reports, "cleanup")) {
      var list = resource(http, evidence); String key = list.key();
      seedReplies(ingress, key);
      ingress.reply("GET", path(key), 200, present(key)).replyText("POST", path(key) + "?action=delete", 200, "ok")
          .reply("GET", path(key), 200, absent(key));
      var error = assertThrows(AssertionError.class, () -> {
        try (list) { list.seed("payload"); throw new AssertionError("test failed"); }
      });
      assertEquals("test failed", error.getMessage());
      assertEquals(0, error.getSuppressed().length);
      list.close();
    }
  }
  @Test void collisionNeverWritesOrDeletes() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(1));
         var evidence = new RunEvidence(reports, "collision"); var list = resource(http, evidence)) {
      ingress.reply("GET", path(list.key()), 200, present(list.key()));
      assertThrows(AssertionError.class, () -> list.seed("payload"));
    }
  }
  @Test void failedWriteStillRemovesItsObservedEffect() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(1));
         var evidence = new RunEvidence(reports, "unknown")) {
      var list = resource(http, evidence); String key = list.key();
      ingress.reply("GET", path(key), 200, absent(key)).reply("POST", path(key), 500, Map.of())
          .reply("GET", path(key), 200, present(key)).replyText("POST", path(key) + "?action=delete", 200, "ok")
          .reply("GET", path(key), 200, absent(key));
      var error = assertThrows(ApiException.class, () -> { try (list) { list.seed("payload"); } });
      assertEquals(0, error.getSuppressed().length);
    }
  }
  @Test void unknownWriteThenAbsenceIsNotReportedAsSuccessfulCleanup() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(1));
         var evidence = new RunEvidence(reports, "unconfirmed")) {
      var list = resource(http, evidence); String key = list.key();
      ingress.reply("GET", path(key), 200, absent(key)).reply("POST", path(key), 500, Map.of())
          .reply("GET", path(key), 200, absent(key));
      var error = assertThrows(ApiException.class, () -> { try (list) { list.seed("payload"); } });
      assertEquals(1, error.getSuppressed().length);
      assertTrue(error.getSuppressed()[0].getMessage().contains("Unconfirmed Redis write"));
    }
  }
  @Test void deleteAcknowledgementDoesNotHideRemainingKey() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(1));
         var evidence = new RunEvidence(reports, "remaining")) {
      var list = resource(http, evidence); String key = list.key();
      seedReplies(ingress, key);
      ingress.reply("GET", path(key), 200, present(key)).replyText("POST", path(key) + "?action=delete", 200, "ok")
          .reply("GET", path(key), 200, present(key));
      var error = assertThrows(AssertionError.class, () -> { try (list) { list.seed("payload"); } });
      assertTrue(error.getMessage().contains("remains after delete"));
    }
  }
  @Test void wrongKeyResponseFailsBeforeMutation() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(1));
         var evidence = new RunEvidence(reports, "identity"); var list = resource(http, evidence)) {
      ingress.reply("GET", path(list.key()), 200, absent("foreign"));
      assertThrows(AssertionError.class, () -> list.seed("payload"));
    }
  }
  @Test void consumedAcquiredListNeedsNoDelete() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(1));
         var evidence = new RunEvidence(reports, "consumed"); var list = resource(http, evidence)) {
      seedReplies(ingress, list.key());
      ingress.reply("GET", path(list.key()), 200, absent(list.key()));
      list.seed("payload");
    }
  }

  @Test void producerReservationDeletesOnlyItsLaterCreatedKey() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(1));
         var evidence = new RunEvidence(reports, "producer")) {
      var list = resource(http, evidence); String key = list.key();
      ingress.reply("GET", path(key), 200, absent(key))
          .reply("GET", path(key), 200, present(key)).replyText("POST", path(key) + "?action=delete", 200, "ok")
          .reply("GET", path(key), 200, absent(key));
      try (list) {
        list.reserveForProducer();
        assertThrows(IllegalStateException.class, () -> list.seed("cannot overwrite reservation"));
      }
    }
  }
  @Test void unusedProducerReservationNeedsNoDelete() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(1));
         var evidence = new RunEvidence(reports, "empty-producer"); var list = resource(http, evidence)) {
      ingress.reply("GET", path(list.key()), 200, absent(list.key()))
          .reply("GET", path(list.key()), 200, absent(list.key()));
      list.reserveForProducer();
    }
  }
  @Test void producerReservationCannotClaimAnExistingList() throws Exception {
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), Duration.ofSeconds(1));
         var evidence = new RunEvidence(reports, "producer-collision"); var list = resource(http, evidence)) {
      ingress.reply("GET", path(list.key()), 200, present(list.key()));
      assertThrows(AssertionError.class, list::reserveForProducer);
    }
  }
}
