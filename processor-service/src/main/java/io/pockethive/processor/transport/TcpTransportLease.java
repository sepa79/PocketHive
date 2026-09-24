package io.pockethive.processor.transport;

/**
 * Responsibility: execute through the selected transport and release a non-reused transport at scope end.
 * Must not: retry, select/configure transports or interpret protocol results.
 * Contract: RESP-PROCESSOR-TCP-RUNTIME — docs/architecture/runtime-responsibilities.md#resp-processor-tcp-runtime.
 */
public final class TcpTransportLease implements AutoCloseable {
  private final TcpTransport transport;
  private final boolean closeAfter;

  TcpTransportLease(TcpTransport transport, boolean closeAfter) {
    this.transport = transport;
    this.closeAfter = closeAfter;
  }

  public TcpResponse execute(TcpRequest request, TcpBehavior behavior) throws TcpException {
    return transport.execute(request, behavior);
  }

  @Override
  public void close() {
    if (closeAfter && transport != null) {
      try {
        transport.close();
      } catch (Exception ignored) {
      }
    }
  }
}
