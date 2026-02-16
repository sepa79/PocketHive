package io.pockethive.postprocessor;

public record PostProcessorWorkerConfig(
    boolean forwardToOutput,
    boolean writeTxOutcomeToClickHouse,
    boolean dropTxOutcomeWithoutCallId
) {

  public PostProcessorWorkerConfig(boolean forwardToOutput) {
    this(forwardToOutput, false, true);
  }

  public PostProcessorWorkerConfig(boolean forwardToOutput, boolean writeTxOutcomeToClickHouse) {
    this(forwardToOutput, writeTxOutcomeToClickHouse, true);
  }
}
