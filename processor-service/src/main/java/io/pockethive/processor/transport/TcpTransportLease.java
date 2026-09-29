package io.pockethive.processor.transport;

import io.pockethive.processor.TcpTransportConfig;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Responsibility: pin a configured transport until the caller finishes its result/error path.
 * Must not: retry, select/configure transports or interpret protocol results.
 * Contract: RESP-PROCESSOR-TCP-RUNTIME — docs/architecture/runtime-responsibilities.md#resp-processor-tcp-runtime.
 */
public final class TcpTransportLease implements AutoCloseable {
  private final TcpTransport transport;
  private final TcpTransportConfig config;
  private final Runnable release;
  private final AtomicBoolean closed = new AtomicBoolean();

  TcpTransportLease(TcpTransport transport, TcpTransportConfig config, Runnable release) {
    this.transport = transport;
    this.config = config;
    this.release = release;
  }

  public TcpTransportConfig config() { return config; }

  public TcpResponse execute(TcpRequest request, TcpBehavior behavior) throws TcpException {
    if (closed.get()) throw new IllegalStateException("TCP transport lease is closed");
    return transport.execute(request, behavior);
  }

  @Override public void close() {
    if (closed.compareAndSet(false, true)) release.run();
  }
}
