package io.pockethive.httpsequence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.requesttemplates.files.TemplateLoader;
import io.pockethive.work.api.PocketHiveWorkerFunction;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerContext;
import io.pockethive.work.api.PocketHiveWorker;
import io.pockethive.work.api.WorkerCapability;
import io.pockethive.worker.sdk.config.RedisSequenceProperties;
import io.pockethive.templating.api.TemplateRenderer;
import java.time.Clock;
import java.io.IOException;
import jakarta.annotation.PreDestroy;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component("httpSequenceWorker")
@PocketHiveWorker(
    capabilities = {WorkerCapability.MESSAGE_DRIVEN, WorkerCapability.HTTP},
    config = HttpSequenceWorkerConfig.class
)
/**
 * Responsibility: delegate Work invocations and close this worker's owned runner and HTTP clients.
 * Shutdown consumes RESP-HTTP-SEQUENCE-DEBUG-CAPTURE.
 * Must not: own another service's lifecycle or reimplement the shared template engine.
 * Contract: RESP-HTTP-SEQUENCE-WORK — docs/architecture/runtime-responsibilities.md#resp-http-sequence-work.
 */
class HttpSequenceWorkerImpl implements PocketHiveWorkerFunction, AutoCloseable {

  private static final int GLOBAL_MAX_CONNECTIONS = 200;
  private static final int GLOBAL_MAX_PER_ROUTE = 200;

  private final HttpSequenceRunner runner;
  private final CloseableHttpClient httpClient;
  private final CloseableHttpClient portableHttpClient;
  private boolean closed;

  @Autowired
  HttpSequenceWorkerImpl(
      ObjectMapper mapper,
      HttpSequenceWorkerProperties properties,
      TemplateRenderer templateRenderer,
      RedisSequenceProperties redisProperties
  ) {
    this.httpClient = newPooledClient();
    try {
      this.portableHttpClient = newPortableClient();
    } catch (RuntimeException | Error failure) {
      closeAfterFailure(httpClient, failure);
      throw failure;
    }
    try {
      this.runner = new HttpSequenceRunner(
        mapper,
        Clock.systemUTC(),
        templateRenderer,
        new TemplateLoader(),
        new ApacheHttpCallExecutor(httpClient, portableHttpClient),
        new DefaultHttpSequenceTargetResolver(),
        redisProperties
      );
    } catch (RuntimeException | Error failure) {
      closeAfterFailure(portableHttpClient, failure);
      closeAfterFailure(httpClient, failure);
      throw failure;
    }
  }

  @Override
  public WorkItem onMessage(WorkItem seed, WorkerContext context) {
    HttpSequenceWorkerConfig config = context.requireConfig(HttpSequenceWorkerConfig.class);
    return runner.run(seed, context, config);
  }

  @PreDestroy
  @Override
  public synchronized void close() throws IOException {
    if (!closed) {
      closed = true;
      try (httpClient; portableHttpClient; runner) {
        // Reverse declaration order closes capture first and always attempts the HTTP pool.
      }
    }
  }

  private static CloseableHttpClient newPooledClient() {
    return HttpClients.custom().setConnectionManager(newConnectionManager()).build();
  }

  private static CloseableHttpClient newPortableClient() {
    return HttpClients.custom().setConnectionManager(newConnectionManager())
        .disableAutomaticRetries().disableRedirectHandling().disableCookieManagement().disableAuthCaching().build();
  }

  private static PoolingHttpClientConnectionManager newConnectionManager() {
    PoolingHttpClientConnectionManager manager = new PoolingHttpClientConnectionManager();
    manager.setMaxTotal(GLOBAL_MAX_CONNECTIONS);
    manager.setDefaultMaxPerRoute(GLOBAL_MAX_PER_ROUTE);
    return manager;
  }

  private static void closeAfterFailure(CloseableHttpClient client, Throwable failure) {
    try {
      client.close();
    } catch (IOException | RuntimeException | Error cleanupFailure) {
      if (failure != cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
    }
  }
}
