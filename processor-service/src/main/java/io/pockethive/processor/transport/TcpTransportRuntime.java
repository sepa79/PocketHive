package io.pockethive.processor.transport;

import io.pockethive.processor.TcpTransportConfig;
import java.util.Objects;
import java.util.function.Function;

/**
 * Responsibility: atomically acquire configured transports and retire generations after their last lease.
 * Must not: interpret protocols, retry requests, pace work or construct results.
 * Contract: RESP-PROCESSOR-TCP-RUNTIME — docs/architecture/runtime-responsibilities.md#resp-processor-tcp-runtime.
 */
public final class TcpTransportRuntime implements AutoCloseable {
  private final Function<TcpTransportConfig, TcpTransport> factory;
  private TcpTransportGeneration active;
  private boolean closed;

  public TcpTransportRuntime() { this(TcpTransportFactory::create); }

  TcpTransportRuntime(Function<TcpTransportConfig, TcpTransport> factory) {
    this.factory = Objects.requireNonNull(factory, "factory");
  }

  public TcpTransportLease acquire(TcpTransportConfig config) {
    Objects.requireNonNull(config, "processor tcpTransport config");
    TcpTransportGeneration toClose = null;
    TcpTransportLease lease;
    synchronized (this) {
      if (closed) throw new IllegalStateException("TCP transport runtime is closed");
      TcpTransportGeneration selected = active;
      boolean replacement = selected == null || !selected.config.equals(config);
      if (replacement) selected = new TcpTransportGeneration(config, factory);
      TcpTransport transport;
      try {
        transport = selected.select(factory);
      } catch (RuntimeException | Error failure) {
        if (replacement) selected.close();
        throw failure;
      }
      if (replacement) {
        if (active != null) {
          active.retired = true;
          if (active.leases == 0) toClose = active;
        }
        active = selected;
      }
      selected.leases++;
      var generation = selected;
      lease = new TcpTransportLease(transport, config, () -> release(generation, transport));
    }
    if (toClose != null) toClose.close();
    return lease;
  }

  private void release(TcpTransportGeneration generation, TcpTransport transport) {
    boolean dispose;
    synchronized (this) {
      generation.leases--;
      dispose = generation.retired && generation.leases == 0;
    }
    if (generation.config.connectionReuse() == TcpTransportConfig.ConnectionReuse.NONE) {
      TcpTransportGeneration.closeTransport(transport);
    }
    if (dispose) generation.close();
  }

  @Override public void close() {
    TcpTransportGeneration toClose;
    synchronized (this) {
      if (closed) return;
      closed = true;
      toClose = active;
      active = null;
      if (toClose != null) {
        toClose.retired = true;
        if (toClose.leases != 0) toClose = null;
      }
    }
    if (toClose != null) toClose.close();
  }
}
