package io.pockethive.processor;

import io.pockethive.swarm.model.BeeConfigKeys;
import io.pockethive.worker.sdk.config.MaxInFlightConfig;
import java.util.Map;
import java.util.Objects;

public record ProcessorWorkerConfig(
    String baseUrl,
    Mode mode,
    Integer threadCount,
    Double ratePerSec,
    ConnectionReuse connectionReuse,
    Boolean keepAlive,
    Integer timeoutMs,
    Boolean sslVerify,
    TcpTransportConfig tcpTransport,
    Map<String, Object> privateConfig
) implements MaxInFlightConfig {

  public ProcessorWorkerConfig(String baseUrl,
                               Mode mode,
                               Integer threadCount,
                               Double ratePerSec,
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
    baseUrl = requireNonBlank(baseUrl, "baseUrl");
    mode = Objects.requireNonNull(mode, "mode");
    threadCount = requirePositive(threadCount, "threadCount");
    if (mode == Mode.RATE_PER_SEC) {
      ratePerSec = requirePositive(ratePerSec, "ratePerSec");
    }
    connectionReuse = connectionReuse == null ? ConnectionReuse.GLOBAL : connectionReuse;
    keepAlive = keepAlive == null ? Boolean.TRUE : keepAlive;
    timeoutMs = timeoutMs == null || timeoutMs <= 0 ? 30000 : timeoutMs;
    sslVerify = sslVerify == null ? Boolean.FALSE : sslVerify;
    tcpTransport = tcpTransport == null ? TcpTransportConfig.defaults() : tcpTransport;
    privateConfig = privateConfig == null ? Map.of() : Map.copyOf(privateConfig);
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> authProfileSutContext() {
    Object authProfile = privateConfig.get(BeeConfigKeys.AUTH_PROFILE);
    if (!(authProfile instanceof Map<?, ?> rawAuthProfile)) {
      return Map.of();
    }
    Object sut = rawAuthProfile.get(BeeConfigKeys.SUT);
    if (sut instanceof Map<?, ?> rawSut) {
      return (Map<String, Object>) rawSut;
    }
    return Map.of();
  }

  @Override
  public int maxInFlight() {
    return threadCount;
  }

  private static String requireNonBlank(String candidate, String field) {
    Objects.requireNonNull(candidate, field);
    String trimmed = candidate.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return trimmed;
  }

  private static int requirePositive(Integer candidate, String field) {
    if (candidate == null || candidate <= 0) {
      throw new IllegalArgumentException(field + " must be positive");
    }
    return candidate;
  }

  private static double requirePositive(Double candidate, String field) {
    if (candidate == null || !Double.isFinite(candidate) || candidate <= 0.0) {
      throw new IllegalArgumentException(field + " must be positive");
    }
    return candidate;
  }
}
