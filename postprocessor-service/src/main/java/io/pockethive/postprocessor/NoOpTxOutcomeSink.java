package io.pockethive.postprocessor;

import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class NoOpTxOutcomeSink implements TxOutcomeSink {

  @Override
  public TxOutcomeSinkMode mode() {
    return TxOutcomeSinkMode.NONE;
  }

  @Override
  public void write(TxOutcomeEvent event) {
    Objects.requireNonNull(event, "event");
  }
}
