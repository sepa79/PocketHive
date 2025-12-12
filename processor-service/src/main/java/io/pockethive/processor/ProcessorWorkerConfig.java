package io.pockethive.processor;

import io.pockethive.worker.sdk.config.MaxInFlightConfig;

public record ProcessorWorkerConfig(
    String baseUrl,
    Mode mode,
    int threadCount,
    double ratePerSec,
    ConnectionReuse connectionReuse,
    Boolean keepAlive,
    Integer timeoutMs,
    Boolean sslVerify,
    TcpTransportConfig tcpTransport
) implements MaxInFlightConfig {

  public enum Mode {
    THREAD_COUNT,
    RATE_PER_SEC
  }

  public enum ConnectionReuse {
    GLOBAL,
    PER_THREAD,
    NONE
  }

  public ProcessorWorkerConfig {
    baseUrl = sanitise(baseUrl);
    mode = mode == null ? Mode.THREAD_COUNT : mode;
    threadCount = threadCount <= 0 ? 1 : threadCount;
    ratePerSec = ratePerSec <= 0.0 ? 1.0 : ratePerSec;
    connectionReuse = connectionReuse == null ? ConnectionReuse.GLOBAL : connectionReuse;
    keepAlive = keepAlive == null ? Boolean.TRUE : keepAlive;
    timeoutMs = timeoutMs == null || timeoutMs <= 0 ? 30000 : timeoutMs;
    sslVerify = sslVerify == null ? Boolean.FALSE : sslVerify;
    tcpTransport = tcpTransport == null ? TcpTransportConfig.defaults() : tcpTransport;
  }

  @Override
  public int maxInFlight() {
    return threadCount;
  }

  private static String sanitise(String candidate) {
    if (candidate == null) {
      return null;
    }
    String trimmed = candidate.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
