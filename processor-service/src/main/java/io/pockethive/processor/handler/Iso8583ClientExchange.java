package io.pockethive.processor.handler;

import io.pockethive.processor.ProcessorWorkerConfig;
import io.pockethive.processor.TcpTransportConfig;
import io.pockethive.processor.transport.TcpBehavior;
import io.pockethive.processor.transport.TcpRequest;
import io.pockethive.processor.transport.TcpTransportLease;
import io.pockethive.processor.transport.TcpTransportRuntime;
import io.pockethive.work.api.WorkerContext;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Responsibility: execute a configured ISO client byte exchange, including framing and bounded client retries.
 * Must not: own pools or generations, parse envelopes or construct Work results.
 * Contract: RESP-PROCESSOR-EXECUTE — docs/architecture/runtime-responsibilities.md#resp-processor-execute;
 * transport lifetime delegates to RESP-PROCESSOR-TCP-RUNTIME — docs/architecture/runtime-responsibilities.md#resp-processor-tcp-runtime.
 */
final class Iso8583ClientExchange implements AutoCloseable {
  private final TcpTransportRuntime transportRuntime;

  Iso8583ClientExchange(TcpTransportRuntime transportRuntime) {
    this.transportRuntime = Objects.requireNonNull(transportRuntime, "transportRuntime");
  }

  byte[] exchange(byte[] payload, Iso8583WireProfile profile, Iso8583Endpoint endpoint,
                  ProcessorWorkerConfig config, Map<String, Object> authOptions, WorkerContext context)
      throws Exception {
    TcpTransportConfig desired = Objects.requireNonNull(config.tcpTransport(),
        "processor tcpTransport config must be provided by runtime config");
    try (TcpTransportLease transport = transportRuntime.acquire(desired)) {
      TcpTransportConfig selected = transport.config();
      Map<String, Object> options = new HashMap<>();
      options.put("connectTimeoutMs", selected.connectTimeoutMs());
      options.put("readTimeoutMs", selected.readTimeoutMs());
      options.put("maxBytes", selected.maxBytes());
      options.put("ssl", endpoint.tls());
      options.put("sslVerify", selected.sslVerify());
      options.putAll(authOptions);
      TcpRequest request = new TcpRequest(endpoint.host(), endpoint.port(), profile.frame(payload), options);
      for (int attempt = 0; ; attempt++) {
        try {
          return transport.execute(request, TcpBehavior.LENGTH_PREFIX_2B).body();
        } catch (Exception ex) {
          if (attempt >= selected.maxRetries()) {
            throw ex;
          }
          context.logger().warn("ISO8583 attempt {} failed, retrying: {}", attempt + 1, ex.getMessage());
          Thread.sleep(100L * (attempt + 1));
        }
      }
    }
  }

  @Override
  public void close() {
    transportRuntime.close();
  }
}
