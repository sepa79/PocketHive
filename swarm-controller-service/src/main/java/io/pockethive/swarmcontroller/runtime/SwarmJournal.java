package io.pockethive.swarmcontroller.runtime;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Swarm-scoped journal abstraction.
 * <p>
 * Implementations are responsible for persisting append-only journal entries
 * (for example to a per-swarm JSONL file in the runtime directory).
 */
public interface SwarmJournal {

  void append(SwarmJournalEntry entry);

  /**
   * Convenience factory for a no-op journal, useful for tests or when
   * journaling is disabled.
   */
  static SwarmJournal noop() {
    return entry -> {
      // intentionally no-op
    };
  }

  /**
   * Minimal swarm-level journal entry.
   * <p>
   * This is intentionally generic so it can be projected from existing
   * control-plane events and runtime actions without changing on-wire
   * contracts. Fields are chosen to stay replay-friendly.
   */
  record SwarmJournalEntry(
      Instant timestamp,
      String swarmId,
      String actor,
      String kind,
      String severity,
      String correlationId,
      String idempotencyKey,
      String message,
      Map<String, Object> details) {

    public SwarmJournalEntry {
      Objects.requireNonNull(timestamp, "timestamp");
      Objects.requireNonNull(swarmId, "swarmId");
      Objects.requireNonNull(actor, "actor");
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(severity, "severity");
      // correlationId, idempotencyKey and details are allowed to be null
    }

    public static SwarmJournalEntry info(String swarmId,
                                         String actor,
                                         String kind,
                                         String message,
                                         Map<String, Object> details) {
      return new SwarmJournalEntry(
          Instant.now(),
          swarmId,
          actor,
          kind,
          "INFO",
          null,
          null,
          message,
          details);
    }

    public static SwarmJournalEntry error(String swarmId,
                                          String actor,
                                          String kind,
                                          String message,
                                          Map<String, Object> details) {
      return new SwarmJournalEntry(
          Instant.now(),
          swarmId,
          actor,
          kind,
          "ERROR",
          null,
          null,
          message,
          details);
    }
  }
}

