package io.pockethive.acceptance.resources;

import static org.junit.jupiter.api.Assertions.*;
import static io.pockethive.acceptance.support.OperationFixtures.*;
import io.pockethive.acceptance.api.*;
import io.pockethive.acceptance.config.WaitLimits;
import io.pockethive.acceptance.evidence.RunEvidence;
import io.pockethive.acceptance.operations.OperationAwaiter;
import io.pockethive.acceptance.support.ScriptedIngress;
import io.pockethive.swarm.model.lifecycle.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SwarmResourceTest {
  @TempDir Path reports;
  private final WaitLimits limits = new WaitLimits(Duration.ofSeconds(1), Duration.ofSeconds(2),
      Duration.ofSeconds(1), Duration.ofMillis(1));
  private static final String BASE = "/orchestrator/api/swarms/" + SWARM;

  private SwarmResource resource(PocketHiveHttp http, RunEvidence evidence) {
    var api = new SwarmApi(http, "");
    return new SwarmResource(SWARM, api, new OperationAwaiter(api, limits.operations(), evidence), limits.operations());
  }
  private void acquired(ScriptedIngress ingress, ControlResponse create) {
    ingress.reply("POST", BASE + "/create", 202, create)
        .reply("GET", "/orchestrator" + create.operationUrl(), 200, succeeded(create, OperationType.CREATE));
  }
  private Supplier<ControlResponse> acknowledge(ScriptedIngress ingress, String action, ControlResponse seed) {
    var accepted = new AtomicReference<ControlResponse>();
    ingress.replyWith("POST", BASE + action, 202, request -> {
      String key = request.required("idempotencyKey").textValue();
      assertNotNull(key);
      assertFalse(key.isBlank());
      var receipt = new ControlResponse(seed.correlationId(), key, seed.operationUrl(), seed.outcomeTopic(), seed.timeoutMs());
      accepted.set(receipt);
      return receipt;
    });
    return accepted::get;
  }
  private void completed(ScriptedIngress ingress, String action, ControlResponse seed, OperationType type) {
    var accepted = acknowledge(ingress, action, seed);
    ingress.replyWith("GET", "/orchestrator" + seed.operationUrl(), 200, ignored -> succeeded(accepted.get(), type));
  }
  private void removed(ScriptedIngress ingress, ControlResponse remove) {
    var accepted = acknowledge(ingress, "/remove", remove);
    ingress.replyWith("GET", "/orchestrator" + remove.operationUrl(), 200,
            ignored -> operation(accepted.get(), OperationType.REMOVE, OperationState.DISPATCHED, Map.of()))
        .replyWith("GET", "/orchestrator" + remove.operationUrl(), 200, ignored -> succeeded(accepted.get(), OperationType.REMOVE))
        .reply("GET", BASE, 404, Map.of());
  }

  @Test void assertionFailureStillWaitsForVerifiedRemoval() throws Exception {
    var create = receipt(); var remove = receipt();
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), limits.request());
        var evidence = new RunEvidence(reports, "resource")) {
      acquired(ingress, create); removed(ingress, remove);
      var swarm = resource(http, evidence);
      var primary = new AssertionError("Test body failed");
      var observed = assertThrows(AssertionError.class, () -> {
        try (swarm) { swarm.create(createRequest(create)); throw primary; }
      });
      assertSame(primary, observed);
      assertEquals(0, observed.getSuppressed().length);
      assertEquals(remove.correlationId(), swarm.removal().correlationId());
    }
  }
  @Test void successfulExplicitRemovalIsNotSentAgainByClose() throws Exception {
    var create = receipt(); var remove = receipt();
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), limits.request());
        var evidence = new RunEvidence(reports, "resource")) {
      acquired(ingress, create); removed(ingress, remove);
      try (var swarm = resource(http, evidence)) { swarm.create(createRequest(create)); swarm.remove(); }
    }
  }
  @Test void failedRemovalRemainsVisibleAlongsideTheOriginalFailure() throws Exception {
    var create = receipt(); var remove = receipt();
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), limits.request());
        var evidence = new RunEvidence(reports, "resource")) {
      acquired(ingress, create);
      var accepted = acknowledge(ingress, "/remove", remove);
      ingress.replyWith("GET", "/orchestrator" + remove.operationUrl(), 200,
          ignored -> operation(accepted.get(), OperationType.REMOVE, OperationState.FAILED, Map.of("errors", List.of("resource remains"))));
      var primary = new AssertionError("Test body failed");
      var observed = assertThrows(AssertionError.class, () -> {
        try (var swarm = resource(http, evidence)) { swarm.create(createRequest(create)); throw primary; }
      });
      assertSame(primary, observed);
      assertEquals(1, observed.getSuppressed().length);
      assertTrue(observed.getSuppressed()[0].getMessage().contains(remove.correlationId()));
    }
  }
  @Test void aRegistry404CannotReplaceSuccessfulRemoveEvidence() throws Exception {
    var create = receipt(); var remove = receipt();
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), limits.request());
        var evidence = new RunEvidence(reports, "resource")) {
      acquired(ingress, create);
      var accepted = acknowledge(ingress, "/remove", remove);
      ingress.replyWith("GET", "/orchestrator" + remove.operationUrl(), 200,
          ignored -> operation(accepted.get(), OperationType.REMOVE, OperationState.SUCCEEDED,
              Map.of("removedResources", List.of("one"), "remainingResources", List.of("still there"), "errors", List.of())));
      var error = assertThrows(AssertionError.class, () -> {
        try (var swarm = resource(http, evidence)) { swarm.create(createRequest(create)); }
      });
      assertTrue(error.getMessage().contains("cleanup evidence"));
    }
  }
  @Test void rejectedCreateDoesNotDeleteAnUnownedSwarm() throws Exception {
    var create = receipt();
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), limits.request());
        var evidence = new RunEvidence(reports, "resource")) {
      ingress.reply("POST", BASE + "/create", 403, Map.of("message", "denied"));
      var error = assertThrows(ApiException.class, () -> {
        try (var swarm = resource(http, evidence)) { swarm.create(createRequest(create)); }
      });
      assertEquals(0, error.getSuppressed().length);
    }
  }
  @Test void unconfirmedCreateIsReportedInsteadOfGuessingOwnership() throws Exception {
    var create = receipt();
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), limits.request());
        var evidence = new RunEvidence(reports, "resource")) {
      ingress.reply("POST", BASE + "/create", 500, Map.of("message", "dispatch uncertain"));
      var error = assertThrows(ApiException.class, () -> {
        try (var swarm = resource(http, evidence)) { swarm.create(createRequest(create)); }
      });
      assertEquals(1, error.getSuppressed().length);
      assertTrue(error.getSuppressed()[0].getMessage().contains("unconfirmed"));
    }
  }

  @ParameterizedTest
  @EnumSource(value = OperationType.class, names = {"CREATE", "START", "STOP", "REMOVE"})
  void evidenceWriteFailureIsReportedAfterVerifiedCleanup(OperationType phase) throws Exception {
    var receipts = Map.of(OperationType.CREATE, receipt(), OperationType.START, receipt(),
        OperationType.STOP, receipt(), OperationType.REMOVE, receipt());
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), limits.request())) {
      var evidence = new RunEvidence(reports, "broken-artifact");
      Files.createDirectory(evidence.directory().resolve("operation-" + receipts.get(phase).correlationId() + ".json"));
      acquired(ingress, receipts.get(OperationType.CREATE));
      completed(ingress, "/start", receipts.get(OperationType.START), OperationType.START);
      completed(ingress, "/stop", receipts.get(OperationType.STOP), OperationType.STOP);
      removed(ingress, receipts.get(OperationType.REMOVE));
      var swarm = resource(http, evidence);
      var failure = assertThrows(IOException.class, () -> {
        try (evidence; swarm) {
          swarm.create(createRequest(receipts.get(OperationType.CREATE)));
          swarm.start();
          swarm.stop();
          swarm.remove();
        }
      });
      assertTrue(failure.getMessage().contains(receipts.get(phase).correlationId()));
      assertEquals(receipts.get(OperationType.REMOVE).correlationId(), swarm.removal().correlationId());
    }
  }

  @Test void reportingAndCleanupFailuresBothRemainAlongsideThePrimaryFailure() throws Exception {
    var create = receipt(); var remove = receipt();
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), limits.request())) {
      var evidence = new RunEvidence(reports, "multiple-failures");
      Files.createDirectory(evidence.directory().resolve("operation-" + create.correlationId() + ".json"));
      acquired(ingress, create);
      var accepted = acknowledge(ingress, "/remove", remove);
      ingress.replyWith("GET", "/orchestrator" + remove.operationUrl(), 200,
          ignored -> operation(accepted.get(), OperationType.REMOVE, OperationState.FAILED, Map.of("reason", "cannot remove")));
      var primary = new AssertionError("Test body failed");
      var failure = assertThrows(AssertionError.class, () -> {
        try (evidence; var swarm = resource(http, evidence)) {
          swarm.create(createRequest(create));
          throw primary;
        }
      });
      assertSame(primary, failure);
      assertEquals(2, failure.getSuppressed().length);
      assertTrue(failure.getSuppressed()[0].getMessage().contains(remove.correlationId()));
      assertInstanceOf(IOException.class, failure.getSuppressed()[1]);
    }
  }

  @ParameterizedTest
  @EnumSource(value = OperationType.class, names = {"CREATE", "START", "STOP", "REMOVE"})
  void mismatchedReceiptFailsButStillObservesAndCleansOwnedResources(OperationType phase) throws Exception {
    var create = receipt(); var wrong = receipt(); var remove = receipt();
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), limits.request());
        var evidence = new RunEvidence(reports, "mismatched-receipt")) {
      if (phase != OperationType.CREATE) acquired(ingress, create);
      String action = switch (phase) {
        case CREATE -> "/create"; case START -> "/start"; case STOP -> "/stop"; case REMOVE -> "/remove";
        default -> throw new AssertionError(phase);
      };
      ingress.reply("POST", BASE + action, 202, wrong)
          .reply("GET", "/orchestrator" + wrong.operationUrl(), 200, operation(wrong, phase, OperationState.DISPATCHED, Map.of()))
          .reply("GET", "/orchestrator" + wrong.operationUrl(), 200, succeeded(wrong, phase));
      if (phase == OperationType.REMOVE) ingress.reply("GET", BASE, 404, Map.of());
      else removed(ingress, remove);
      var swarm = resource(http, evidence);
      var failure = assertThrows(ControlReceiptMismatchException.class, () -> {
        try (swarm) {
          swarm.create(createRequest(create));
          switch (phase) {
            case START -> swarm.start(); case STOP -> swarm.stop(); case REMOVE -> swarm.remove();
            default -> fail("Mismatched create should have failed");
          }
        }
      });
      assertEquals(wrong, failure.receipt());
      assertEquals(0, failure.getSuppressed().length);
      assertEquals(phase == OperationType.REMOVE ? wrong.correlationId() : remove.correlationId(),
          swarm.removal().correlationId());
    }
  }

  @Test void mismatchDuringAutomaticRemoveDoesNotSkipRemovalVerification() throws Exception {
    var create = receipt(); var wrong = receipt();
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), limits.request());
        var evidence = new RunEvidence(reports, "cleanup-mismatch")) {
      acquired(ingress, create);
      ingress.reply("POST", BASE + "/remove", 202, wrong)
          .reply("GET", "/orchestrator" + wrong.operationUrl(), 200, succeeded(wrong, OperationType.REMOVE))
          .reply("GET", BASE, 404, Map.of());
      var swarm = resource(http, evidence);
      var primary = new AssertionError("Test body failed");
      var failure = assertThrows(AssertionError.class, () -> {
        try (swarm) { swarm.create(createRequest(create)); throw primary; }
      });
      assertSame(primary, failure);
      assertEquals(1, failure.getSuppressed().length);
      assertInstanceOf(ControlReceiptMismatchException.class, failure.getSuppressed()[0]);
      assertEquals(wrong.correlationId(), swarm.removal().correlationId());
    }
  }

  @Test void aMismatchedReceiptDoesNotBypassTheExistingRunCheck() throws Exception {
    var create = receipt(); var wrong = receipt();
    var now = java.time.Instant.now();
    var foreign = SwarmOperation.accepted(SWARM, OperationType.START,
        new Target(io.pockethive.swarm.model.BeeRoles.SWARM_CONTROLLER, "controller"),
        new RuntimeMetadata("test-fixture", "another-run"), wrong.correlationId(), wrong.idempotencyKey(),
        now.minusSeconds(1), now.plusSeconds(5))
        .complete(OperationState.SUCCEEDED, new TerminalResult(TerminalStatus.SUCCEEDED, false, Map.of()), now);
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), limits.request());
        var evidence = new RunEvidence(reports, "foreign-run")) {
      acquired(ingress, create);
      ingress.reply("POST", BASE + "/start", 202, wrong)
          .reply("GET", "/orchestrator" + wrong.operationUrl(), 200, foreign)
          .reply("GET", "/orchestrator" + wrong.operationUrl(), 200, foreign);
      var failure = assertThrows(ControlReceiptMismatchException.class, () -> {
        try (var swarm = resource(http, evidence)) { swarm.create(createRequest(create)); swarm.start(); }
      });
      assertEquals(2, failure.getSuppressed().length);
      for (Throwable cause : failure.getSuppressed()) assertTrue(cause.getMessage().contains("different run"));
      // No remove is scripted: foreign run evidence cannot authorize a destructive command.
    }
  }

  @Test void acceptedCreateByAnotherActorIsObservedAndRemovedByOwnerAfterAssertionFailure() throws Exception {
    var create = receipt(); var remove = receipt();
    try (var requesterIngress = new ScriptedIngress(); var ownerIngress = new ScriptedIngress();
         var requesterHttp = new PocketHiveHttp(requesterIngress.origin(), limits.request());
         var ownerHttp = new PocketHiveHttp(ownerIngress.origin(), limits.request());
         var evidence = new RunEvidence(reports, "two-actors")) {
      requesterIngress.reply("POST", BASE + "/create", 202, create);
      ownerIngress.reply("GET", "/orchestrator" + create.operationUrl(), 200, succeeded(create, OperationType.CREATE));
      removed(ownerIngress, remove);
      var swarm = resource(ownerHttp, evidence);
      var primary = new AssertionError("Viewer CREATE unexpectedly succeeded");
      var failure = assertThrows(AssertionError.class, () -> {
        try (swarm) {
          swarm.create(createRequest(create), new SwarmApi(requesterHttp, "viewer-token"));
          throw primary;
        }
      });
      assertSame(primary, failure);
      assertEquals(0, failure.getSuppressed().length);
      assertEquals(remove.correlationId(), swarm.removal().correlationId());
    }
  }

  @Test void rejectedCreateByAnotherActorDoesNotRequestRemoval() throws Exception {
    var create = receipt();
    try (var requesterIngress = new ScriptedIngress(); var ownerIngress = new ScriptedIngress();
         var requesterHttp = new PocketHiveHttp(requesterIngress.origin(), limits.request());
         var ownerHttp = new PocketHiveHttp(ownerIngress.origin(), limits.request());
         var evidence = new RunEvidence(reports, "two-actors-denied");
         var swarm = resource(ownerHttp, evidence)) {
      requesterIngress.reply("POST", BASE + "/create", 403, Map.of());
      var failure = assertThrows(ApiException.class,
          () -> swarm.create(createRequest(create), new SwarmApi(requesterHttp, "viewer-token")));
      assertEquals(403, failure.response().status());
    }
  }

  @Test void rejectedExplicitRemovalIsNotRetriedByClose() throws Exception {
    var create = receipt();
    try (var ingress = new ScriptedIngress(); var http = new PocketHiveHttp(ingress.origin(), limits.request());
         var evidence = new RunEvidence(reports, "remove-denied")) {
      acquired(ingress, create);
      ingress.reply("POST", BASE + "/remove", 403, Map.of());
      var swarm = resource(http, evidence);
      var failure = assertThrows(ApiException.class, () -> {
        try (swarm) {
          swarm.create(createRequest(create));
          swarm.remove();
        }
      });
      assertEquals(403, failure.response().status());
      assertEquals(1, failure.getSuppressed().length);
      assertInstanceOf(AssertionError.class, failure.getSuppressed()[0]);
      assertTrue(failure.getSuppressed()[0].getMessage().contains("Unconfirmed REMOVE"));
      assertThrows(IllegalStateException.class, swarm::removal);
      // No second REMOVE or readback is scripted: rejection must not trigger a retry or claim release.
    }
  }

  @Test void rejectedStopByAnotherActorPreservesOwnedSwarmForAdminStopAndRemoval() throws Exception {
    var create = receipt(); var stop = receipt(); var remove = receipt();
    try (var requesterIngress = new ScriptedIngress(); var ownerIngress = new ScriptedIngress();
         var requesterHttp = new PocketHiveHttp(requesterIngress.origin(), limits.request());
         var ownerHttp = new PocketHiveHttp(ownerIngress.origin(), limits.request());
         var evidence = new RunEvidence(reports, "stop-denied")) {
      acquired(ownerIngress, create);
      requesterIngress.reply("POST", BASE + "/stop", 403, Map.of());
      completed(ownerIngress, "/stop", stop, OperationType.STOP);
      removed(ownerIngress, remove);
      var swarm = resource(ownerHttp, evidence);
      try (swarm) {
        swarm.create(createRequest(create));
        var failure = assertThrows(ApiException.class,
            () -> swarm.stop(new SwarmApi(requesterHttp, "runner-token")));
        assertEquals(403, failure.response().status());
        swarm.stop();
      }
      assertEquals(remove.correlationId(), swarm.removal().correlationId());
    }
  }

  @Test void unexpectedlyAcceptedStopIsObservedBeforeCleanupAfterDenialAssertionFails() throws Exception {
    var create = receipt(); var stop = receipt(); var remove = receipt();
    try (var requesterIngress = new ScriptedIngress(); var ownerIngress = new ScriptedIngress();
         var requesterHttp = new PocketHiveHttp(requesterIngress.origin(), limits.request());
         var ownerHttp = new PocketHiveHttp(ownerIngress.origin(), limits.request());
         var evidence = new RunEvidence(reports, "stop-accepted")) {
      acquired(ownerIngress, create);
      var accepted = acknowledge(requesterIngress, "/stop", stop);
      ownerIngress.replyWith("GET", "/orchestrator" + stop.operationUrl(), 200,
          ignored -> operation(accepted.get(), OperationType.STOP, OperationState.DISPATCHED, Map.of()))
          .replyWith("GET", "/orchestrator" + stop.operationUrl(), 200,
              ignored -> succeeded(accepted.get(), OperationType.STOP));
      removed(ownerIngress, remove);
      var swarm = resource(ownerHttp, evidence);
      var failure = assertThrows(AssertionError.class, () -> {
        try (swarm) {
          swarm.create(createRequest(create));
          assertThrows(ApiException.class, () -> swarm.stop(new SwarmApi(requesterHttp, "runner-token")));
        }
      });
      assertEquals(0, failure.getSuppressed().length);
      assertEquals(remove.correlationId(), swarm.removal().correlationId());
    }
  }

  @Test void unconfirmedStopDoesNotPermitBlindRemoval() throws Exception {
    var create = receipt();
    try (var requesterIngress = new ScriptedIngress(); var ownerIngress = new ScriptedIngress();
         var requesterHttp = new PocketHiveHttp(requesterIngress.origin(), limits.request());
         var ownerHttp = new PocketHiveHttp(ownerIngress.origin(), limits.request());
         var evidence = new RunEvidence(reports, "stop-unconfirmed")) {
      acquired(ownerIngress, create);
      requesterIngress.reply("POST", BASE + "/stop", 500, Map.of());
      var failure = assertThrows(ApiException.class, () -> {
        try (var swarm = resource(ownerHttp, evidence)) {
          swarm.create(createRequest(create));
          swarm.stop(new SwarmApi(requesterHttp, "runner-token"));
        }
      });
      assertEquals(500, failure.response().status());
      assertEquals(1, failure.getSuppressed().length);
      assertTrue(failure.getSuppressed()[0].getMessage().contains("Unconfirmed STOP"));
      // No remove is scripted: the STOP dispatch outcome is unknown.
    }
  }

}
