package io.pockethive.manager.guard;

/**
 * Metrics sink for buffer guard state.
 */
public interface BufferGuardMetrics {

  void update(double averageDepth, double targetDepth, double ratePerSec, int modeCode);

  void close();
}

