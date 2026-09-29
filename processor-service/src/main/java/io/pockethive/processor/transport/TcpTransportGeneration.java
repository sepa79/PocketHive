package io.pockethive.processor.transport;

import io.pockethive.processor.TcpTransportConfig;
import java.util.function.Function;

/**
 * Responsibility: retain transports and lease accounting for one immutable configuration.
 * Must not: publish generations, retry requests or interpret protocols.
 * Contract: RESP-PROCESSOR-TCP-RUNTIME — docs/architecture/runtime-responsibilities.md#resp-processor-tcp-runtime.
 */
final class TcpTransportGeneration {
  final TcpTransportConfig config;
  final TcpTransport global;
  final TcpPerThreadTransports perThread;
  int leases;
  boolean retired;

  TcpTransportGeneration(TcpTransportConfig config, Function<TcpTransportConfig, TcpTransport> factory) {
    this.config = config;
    this.global = config.connectionReuse() == TcpTransportConfig.ConnectionReuse.GLOBAL
        ? java.util.Objects.requireNonNull(factory.apply(config)) : null;
    this.perThread = config.connectionReuse() == TcpTransportConfig.ConnectionReuse.PER_THREAD
        ? new TcpPerThreadTransports(config, factory) : null;
  }

  TcpTransport select(Function<TcpTransportConfig, TcpTransport> factory) {
    return switch (config.connectionReuse()) {
      case GLOBAL -> global;
      case PER_THREAD -> perThread.get();
      case NONE -> java.util.Objects.requireNonNull(factory.apply(config));
    };
  }

  void close() {
    if (global != null) closeTransport(global);
    if (perThread != null) perThread.closeAll();
  }

  static void closeTransport(TcpTransport transport) {
    try { transport.close(); } catch (Exception ignored) { }
  }
}
