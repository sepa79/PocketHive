package io.pockethive.processor.transport;

import io.pockethive.processor.TcpTransportConfig;
import java.util.Objects;
import java.util.function.Function;

/**
 * Responsibility: own one protocol's configured transports and their replacement/selection.
 * Must not: interpret protocols, retry requests, pace work or construct results.
 * Contract: RESP-PROCESSOR-TCP-RUNTIME — docs/architecture/runtime-responsibilities.md#resp-processor-tcp-runtime.
 */
public final class TcpTransportRuntime {
  private final Object transportLock = new Object();
  private final Function<TcpTransportConfig, TcpTransport> factory;
  private volatile TcpTransportConfig activeConfig;
  private volatile TcpTransport globalTransport;
  private volatile TcpPerThreadTransports perThreadTransports;

  public TcpTransportRuntime() {
    this(TcpTransportFactory::create);
  }

  TcpTransportRuntime(Function<TcpTransportConfig, TcpTransport> factory) {
    this.factory = Objects.requireNonNull(factory, "factory");
  }

  public void configure(TcpTransportConfig desired) {
    desired = Objects.requireNonNull(desired, "processor tcpTransport config must be provided by runtime config");
    TcpTransportConfig current = activeConfig;
    if (desired.equals(current)) {
      return;
    }
    synchronized (transportLock) {
      if (!desired.equals(activeConfig)) {
        reload(desired);
      }
    }
  }

  public TcpTransportConfig currentConfig() {
    return activeConfig;
  }

  /** Call after configure; the caller retains its existing configuration snapshot across the exchange. */
  public TcpTransportLease acquire(TcpTransportConfig config) {
    return switch (config.connectionReuse()) {
      case PER_THREAD -> new TcpTransportLease(perThreadTransports.get(), false);
      case GLOBAL -> new TcpTransportLease(globalTransport, false);
      case NONE -> new TcpTransportLease(factory.apply(config), true);
    };
  }

  private void reload(TcpTransportConfig config) {
    TcpTransport previousGlobal = globalTransport;
    TcpPerThreadTransports previousPerThread = perThreadTransports;
    activeConfig = config;
    globalTransport = factory.apply(config);
    perThreadTransports = new TcpPerThreadTransports(config, factory);
    if (previousGlobal != null) {
      try {
        previousGlobal.close();
      } catch (Exception ignored) {
      }
    }
    if (previousPerThread != null) {
      previousPerThread.closeAll();
    }
  }
}
