package io.pockethive.processor;

import io.pockethive.worker.sdk.config.MaxInFlightConfig;
import java.util.Map;

public record ProcessorWorkerConfig(
    String baseUrl,
    Mode mode,
    int threadCount,
    double ratePerSec,
    ConnectionReuse connectionReuse,
    Boolean keepAlive,
    Integer timeoutMs,
    Boolean sslVerify,
    TcpTransportConfig tcpTransport,
    Map<String, Object> privateConfig
) implements MaxInFlightConfig {

  public ProcessorWorkerConfig(String baseUrl,
                               Mode mode,
                               int threadCount,
                               double ratePerSec,
                               ConnectionReuse connectionReuse,
                               Boolean keepAlive,
                               Integer timeoutMs,
                               Boolean sslVerify,
                               TcpTransportConfig tcpTransport) {
    this(baseUrl, mode, threadCount, ratePerSec, connectionReuse, keepAlive, timeoutMs, sslVerify, tcpTransport, Map.of());
  }

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
    privateConfig = privateConfig == null ? Map.of() : Map.copyOf(privateConfig);
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> authProfileSutContext() {
    Object authProfile = privateConfig.get("authProfile");
    if (!(authProfile instanceof Map<?, ?> rawAuthProfile)) {
      return Map.of();
    }
    Object sut = rawAuthProfile.get("sut");
    if (sut instanceof Map<?, ?> rawSut) {
      return (Map<String, Object>) rawSut;
    }
    return Map.of();
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
