package io.pockethive.swarmcontroller.runtime;

/** Signals that a configured swarm journal could not persist a canonical entry. */
public final class SwarmJournalWriteException extends RuntimeException {

  public SwarmJournalWriteException(String message, Throwable cause) {
    super(message, cause);
  }
}
