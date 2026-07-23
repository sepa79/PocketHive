package io.pockethive.orchestrator.domain;

public enum OperationCompletion {
  COMPLETED,
  AWAITING_OBSERVATION,
  ALREADY_TERMINAL,
  NO_MATCH
}
