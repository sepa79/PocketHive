package io.pockethive.postprocessor;

public record PostProcessorWorkerConfig(
    boolean publishAllMetrics,
    boolean forwardToOutput,
    boolean writeTxOutcomeToClickHouse,
    boolean dropTxOutcomeWithoutCallId
) {

  public PostProcessorWorkerConfig(boolean publishAllMetrics) {
    this(publishAllMetrics, false, false, true);
  }

  public PostProcessorWorkerConfig(boolean publishAllMetrics, boolean forwardToOutput) {
    this(publishAllMetrics, forwardToOutput, false, true);
  }
}
