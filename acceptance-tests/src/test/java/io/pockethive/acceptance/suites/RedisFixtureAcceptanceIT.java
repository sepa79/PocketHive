package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.*;
import io.pockethive.acceptance.api.RedisCommanderApi;
import io.pockethive.acceptance.config.TargetLoader;
import io.pockethive.acceptance.resources.RedisListResource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Responsibility: verify isolated Redis fixture lifetime through public ingress.
 * Must not: claim dataset pipeline coverage or use Redis native commands.
 * Contract: RESP-ACCEPTANCE-REDIS-FIXTURE — docs/architecture/acceptance-tests.md#redis-fixture-preparation-da-prerequisite.
 */
@Tag("redis-fixture")
class RedisFixtureAcceptanceIT {
  @Test void preparesAndRemovesOnlyItsOwnKeyEvenAfterAssertionFailure() throws Exception {
    var target = TargetLoader.loadRedisFixture(TargetLoader.selectedFile());
    try (var run = ApiRun.open(target.api(), "redis-fixture")) {
      var api = new RedisCommanderApi(run.http, target.connectionId());
      String preservedKey;
      try (var preserved = new RedisListResource(api, run.evidence)) {
        preservedKey = preserved.key();
        preserved.seed("{\"customer\":\"preserved\",\"value\":\"a + b & żółw\"}");
        var failing = new RedisListResource(api, run.evidence);
        // Catch only the deliberate body failure; seed/cleanup failures must fail this test.
        var deliberate = new AssertionError("deliberate failure after Redis seed");
        var error = assertThrows(AssertionError.class, () -> {
          try (failing) {
            failing.seed("{\"customer\":\"isolated\"}");
            throw deliberate;
          }
        });
        assertSame(deliberate, error);
        assertEquals(0, error.getSuppressed().length, "Cleanup failed");
        assertEquals("none", api.read(failing.key()).required("type").textValue());
        var remaining = preserved.read();
        assertEquals("list", remaining.required("type").textValue());
        assertEquals(1, remaining.required("length").intValue());
      }
      assertEquals("none", api.read(preservedKey).required("type").textValue());
    }
  }
}
