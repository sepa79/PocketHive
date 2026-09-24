package io.pockethive.processor.transport;

import io.pockethive.processor.TcpTransportConfig;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;

/**
 * Responsibility: create and release per-thread transports for one configuration generation.
 * Must not: choose reuse policy, reload configuration, retry or interpret requests.
 * Contract: RESP-PROCESSOR-TCP-RUNTIME — docs/architecture/runtime-responsibilities.md#resp-processor-tcp-runtime.
 */
final class TcpPerThreadTransports {
  private final ConcurrentLinkedQueue<TcpTransport> created = new ConcurrentLinkedQueue<>();
  private final ThreadLocal<TcpTransport> transport;

  TcpPerThreadTransports(TcpTransportConfig config, Function<TcpTransportConfig, TcpTransport> factory) {
    transport = ThreadLocal.withInitial(() -> {
      TcpTransport createdTransport = factory.apply(config);
      created.add(createdTransport);
      return createdTransport;
    });
  }

  TcpTransport get() {
    return transport.get();
  }

  void closeAll() {
    for (TcpTransport transport : created) {
      try {
        transport.close();
      } catch (Exception ignored) {
      }
    }
    created.clear();
  }
}
