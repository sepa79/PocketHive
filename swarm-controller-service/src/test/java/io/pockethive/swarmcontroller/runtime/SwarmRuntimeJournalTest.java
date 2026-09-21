package io.pockethive.swarmcontroller.runtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class SwarmRuntimeJournalTest {

  private final List<SwarmJournal.SwarmJournalEntry> entries = new ArrayList<>();
  private final SwarmRuntimeJournal runtimeJournal =
      new SwarmRuntimeJournal(entries::add, "swarm", "swarm-controller", "controller-1");

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void projectsWorkerEventsWithCurrentOperationContext() {
    MDC.put("correlation_id", "correlation-1");
    MDC.put("idempotency_key", "idempotency-1");

    runtimeJournal.workersPlanned(2, List.of("generator", "processor"));
    runtimeJournal.workersProvisioned(2);

    assertThat(entries).extracting(SwarmJournal.SwarmJournalEntry::type)
        .containsExactly("workers-planned", "workers-provisioned");
    assertThat(entries).allSatisfy(entry -> {
      assertThat(entry.swarmId()).isEqualTo("swarm");
      assertThat(entry.correlationId()).isEqualTo("correlation-1");
      assertThat(entry.idempotencyKey()).isEqualTo("idempotency-1");
      assertThat(entry.scope().instance()).isEqualTo("controller-1");
    });
    assertThat(entries.getFirst().data())
        .containsEntry("workers", 2)
        .containsEntry("roles", List.of("generator", "processor"));
  }

  @Test
  void truncatesInvalidTemplateMessage() {
    runtimeJournal.templateInvalid(new IllegalArgumentException("x".repeat(250)));

    SwarmJournal.SwarmJournalEntry entry = entries.getFirst();
    assertThat(entry.type()).isEqualTo("template-invalid");
    assertThat(entry.severity()).isEqualTo("ERROR");
    assertThat(entry.data().get("message").toString()).hasSize(201).endsWith("…");
  }

  @Test
  void projectsTimelineStepWithoutBorrowingUnrelatedMdcContext() {
    MDC.put("correlation_id", "unrelated-correlation");

    runtimeJournal.onTimelineStarted(Instant.parse("2026-09-02T09:00:00Z"));
    runtimeJournal.onStepFailed(
        "step-1", "stop generator", 500, "stop", "generator", "generator-1", false, "failed");

    assertThat(entries).allSatisfy(entry -> {
      assertThat(entry.correlationId()).isNull();
      assertThat(entry.idempotencyKey()).isNull();
    });
    assertThat(entries.getFirst().data())
        .containsEntry("startedAt", "2026-09-02T09:00:00Z");
    assertThat(entries.get(1).data())
        .containsEntry("stepId", "step-1")
        .containsEntry("message", "failed");
  }
}
