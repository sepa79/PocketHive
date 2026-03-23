package io.pockethive.postprocessor;

public record PostProcessorWorkerConfig(
    boolean forwardToOutput,
    TxOutcomeSinkMode txOutcomeSinkMode,
    boolean dropTxOutcomeWithoutCallId
) {}
