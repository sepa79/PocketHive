package io.pockethive.postprocessor;

interface TxOutcomeSink {

  TxOutcomeSinkMode mode();

  void write(TxOutcomeEvent event) throws Exception;
}
