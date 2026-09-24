package io.pockethive.processor.http;

import io.pockethive.processor.ProcessorWorkerConfig;
import java.io.IOException;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.client5.http.ssl.TrustAllStrategy;
import org.apache.hc.core5.http.ConnectionReuseStrategy;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;

/**
 * Responsibility: own processor HTTP pools, TLS/reuse selection and their capacity projection.
 * Must not: parse Work envelopes, pace calls, normalize configuration or construct results.
 * Contract: RESP-PROCESSOR-HTTP-CLIENT — docs/architecture/runtime-responsibilities.md#resp-processor-http-client.
 */
public final class ApacheProcessorHttpClient implements ProcessorHttpClient {
  private static final int GLOBAL_MAX_CONNECTIONS = 200;
  private static final int GLOBAL_MAX_PER_ROUTE = 200;

  private record HttpClientBundle(HttpClient pooled, HttpClient noKeepAlive, ThreadLocal<HttpClient> perThread) {
  }

  private final HttpClientBundle verifiedClients = newHttpClientBundle(true);
  private final HttpClientBundle insecureClients = newHttpClientBundle(false);

  @Override
  public <T> T execute(ClassicHttpRequest request, HttpClientResponseHandler<T> responseHandler,
                       ProcessorWorkerConfig config) throws IOException {
    return selectClient(config).execute(request, responseHandler);
  }

  @Override
  public int maxConnections(ProcessorWorkerConfig config) {
    if (!Boolean.TRUE.equals(config.keepAlive())) return 0;
    ProcessorWorkerConfig.ConnectionReuse reuse = config.connectionReuse();
    return reuse == ProcessorWorkerConfig.ConnectionReuse.GLOBAL ? GLOBAL_MAX_CONNECTIONS
        : reuse == ProcessorWorkerConfig.ConnectionReuse.PER_THREAD ? config.threadCount() : 0;
  }

  private HttpClient selectClient(ProcessorWorkerConfig config) {
    boolean sslVerify = Boolean.TRUE.equals(config.sslVerify());
    ProcessorWorkerConfig.ConnectionReuse reuse = config.connectionReuse();
    boolean keepAliveEnabled = Boolean.TRUE.equals(config.keepAlive());
    if (!keepAliveEnabled || reuse == ProcessorWorkerConfig.ConnectionReuse.NONE) {
      return sslVerify ? verifiedClients.noKeepAlive() : insecureClients.noKeepAlive();
    }
    if (reuse == ProcessorWorkerConfig.ConnectionReuse.PER_THREAD) {
      return sslVerify ? verifiedClients.perThread().get() : insecureClients.perThread().get();
    }
    return sslVerify ? verifiedClients.pooled() : insecureClients.pooled();
  }

  private static HttpClientBundle newHttpClientBundle(boolean sslVerify) {
    return new HttpClientBundle(
        newHttpClient(sslVerify, true),
        newHttpClient(sslVerify, false),
        ThreadLocal.withInitial(() -> newHttpClient(sslVerify, true)));
  }

  private static HttpClient newHttpClient(boolean sslVerify, boolean keepAlive) {
    PoolingHttpClientConnectionManager manager = newConnectionManager(sslVerify);
    var builder = HttpClients.custom()
        .useSystemProperties()
        .setConnectionManager(manager);
    if (keepAlive) {
      return builder.build();
    }
    ConnectionReuseStrategy noReuse = (request, response, context) -> false;
    return builder
        .setConnectionReuseStrategy(noReuse)
        .build();
  }

  private static PoolingHttpClientConnectionManager newConnectionManager(boolean sslVerify) {
    PoolingHttpClientConnectionManagerBuilder builder = PoolingHttpClientConnectionManagerBuilder.create();
    if (sslVerify) {
      builder.useSystemProperties();
    } else {
      builder.setSSLSocketFactory(insecureSocketFactory());
    }
    PoolingHttpClientConnectionManager manager = builder.build();
    manager.setMaxTotal(GLOBAL_MAX_CONNECTIONS);
    manager.setDefaultMaxPerRoute(GLOBAL_MAX_PER_ROUTE);
    return manager;
  }

  private static SSLConnectionSocketFactory insecureSocketFactory() {
    try {
      return SSLConnectionSocketFactoryBuilder.create()
          .setSslContext(SSLContextBuilder.create().loadTrustMaterial(null, TrustAllStrategy.INSTANCE).build())
          .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
          .build();
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to create insecure HTTP client SSL context", ex);
    }
  }
}
