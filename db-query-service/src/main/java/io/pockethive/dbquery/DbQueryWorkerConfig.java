package io.pockethive.dbquery;

import io.pockethive.worker.sdk.config.MaxInFlightConfig;
import java.util.List;
import java.util.Map;

public record DbQueryWorkerConfig(
    Adapter adapter,
    String templateRoot,
    String serviceId,
    String queryId,
    Integer threadCount,
    Integer queryTimeoutMs,
    Connection connection,
    Pool pool,
    Retry retry,
    Map<String, Object> vars
) implements MaxInFlightConfig {

  public DbQueryWorkerConfig {
    templateRoot = normalise(templateRoot);
    serviceId = normalise(serviceId);
    queryId = normalise(queryId);
    if (templateRoot == null) {
      throw new IllegalArgumentException("templateRoot is required");
    }
    if (threadCount == null || threadCount <= 0) {
      throw new IllegalArgumentException("threadCount must be > 0");
    }
    if (queryTimeoutMs == null || queryTimeoutMs < 0) {
      throw new IllegalArgumentException("queryTimeoutMs must be >= 0");
    }
    if (pool == null) {
      throw new IllegalArgumentException("pool is required");
    }
    if (retry == null) {
      throw new IllegalArgumentException("retry is required");
    }
    vars = vars == null ? Map.of() : Map.copyOf(vars);
  }

  @Override
  public int maxInFlight() {
    return threadCount;
  }

  public enum Adapter {
    POSTGRES("org.postgresql.Driver"),
    ORACLE("oracle.jdbc.OracleDriver");

    private final String driverClassName;

    Adapter(String driverClassName) {
      this.driverClassName = driverClassName;
    }

    String driverClassName() {
      return driverClassName;
    }
  }

  public record Connection(String jdbcUrl, String username, String password) {
    public Connection {
      jdbcUrl = normalise(jdbcUrl);
      username = normalise(username);
      password = password == null ? "" : password;
    }
  }

  public record Pool(
      int maxSize,
      int minIdle,
      long connectionTimeoutMs,
      long validationTimeoutMs,
      long idleTimeoutMs,
      long maxLifetimeMs
  ) {
    public Pool {
      if (maxSize <= 0) {
        throw new IllegalArgumentException("pool.maxSize must be > 0");
      }
      if (minIdle < 0) {
        throw new IllegalArgumentException("pool.minIdle must be >= 0");
      }
      if (minIdle > maxSize) {
        throw new IllegalArgumentException("pool.minIdle must be <= pool.maxSize");
      }
      if (connectionTimeoutMs < 250L) {
        throw new IllegalArgumentException("pool.connectionTimeoutMs must be >= 250");
      }
      if (validationTimeoutMs < 250L) {
        throw new IllegalArgumentException("pool.validationTimeoutMs must be >= 250");
      }
      if (idleTimeoutMs < 0L) {
        throw new IllegalArgumentException("pool.idleTimeoutMs must be >= 0");
      }
      if (maxLifetimeMs < 0L) {
        throw new IllegalArgumentException("pool.maxLifetimeMs must be >= 0");
      }
    }

    static Pool defaults() {
      return new Pool(4, 1, 1000L, 500L, 60000L, 1800000L);
    }
  }

  public record Retry(
      int maxAttempts,
      long initialBackoffMs,
      double backoffMultiplier,
      long maxBackoffMs,
      List<String> on
  ) {
    public Retry {
      if (maxAttempts <= 0) {
        throw new IllegalArgumentException("retry.maxAttempts must be > 0");
      }
      if (initialBackoffMs < 0L) {
        throw new IllegalArgumentException("retry.initialBackoffMs must be >= 0");
      }
      if (backoffMultiplier <= 0.0) {
        throw new IllegalArgumentException("retry.backoffMultiplier must be > 0");
      }
      if (maxBackoffMs < 0L) {
        throw new IllegalArgumentException("retry.maxBackoffMs must be >= 0");
      }
      on = on == null ? List.of() : List.copyOf(on);
    }

    static Retry noRetry() {
      return new Retry(1, 0L, 1.0, 0L, List.of());
    }
  }

  static String normalise(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
