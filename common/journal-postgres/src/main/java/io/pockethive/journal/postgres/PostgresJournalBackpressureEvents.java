package io.pockethive.journal.postgres;

/**
 * Factory for internal backpressure marker events (start/stop).
 * <p>
 * Implementations should return entries that map (via the provided mapper) into {@code journal_event}.
 */
public interface PostgresJournalBackpressureEvents<T> {

  T backpressureStart();

  T backpressureStop(long droppedInfo);
}

