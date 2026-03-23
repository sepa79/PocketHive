package io.pockethive.postprocessor;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class TxOutcomeSinkRegistry {

  private final Map<TxOutcomeSinkMode, TxOutcomeSink> sinksByMode;

  TxOutcomeSinkRegistry(List<TxOutcomeSink> sinks) {
    Objects.requireNonNull(sinks, "sinks");
    EnumMap<TxOutcomeSinkMode, TxOutcomeSink> byMode = new EnumMap<>(TxOutcomeSinkMode.class);
    for (TxOutcomeSink sink : sinks) {
      TxOutcomeSink previous = byMode.put(sink.mode(), sink);
      if (previous != null) {
        throw new IllegalStateException("Multiple tx-outcome sinks registered for mode=" + sink.mode());
      }
    }
    for (TxOutcomeSinkMode mode : TxOutcomeSinkMode.values()) {
      if (!byMode.containsKey(mode)) {
        throw new IllegalStateException("Missing tx-outcome sink for mode=" + mode);
      }
    }
    this.sinksByMode = Map.copyOf(byMode);
  }

  TxOutcomeSink sinkFor(TxOutcomeSinkMode mode) {
    TxOutcomeSink sink = sinksByMode.get(Objects.requireNonNull(mode, "mode"));
    if (sink == null) {
      throw new IllegalStateException("Unsupported tx-outcome sink mode=" + mode);
    }
    return sink;
  }
}
