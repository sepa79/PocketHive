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
 * Responsibility: delegate Work invocations and close this worker's owned runner and HTTP client.
 * Shutdown consumes RESP-HTTP-SEQUENCE-DEBUG-CAPTURE.
 * Must not: own another service's lifecycle or reimplement the shared template engine.
 * Contract: RESP-HTTP-SEQUENCE-WORK — docs/architecture/runtime-responsibilities.md#resp-http-sequence-work.
 */
class HttpSequenceWorkerImpl implements PocketHiveWorkerFunction, AutoCloseable {

  private static final int GLOBAL_MAX_CONNECTIONS = 200;
  private static final int GLOBAL_MAX_PER_ROUTE = 200;

  private final HttpSequenceRunner runner;
  private final CloseableHttpClient httpClient;
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
      this.runner = new HttpSequenceRunner(
        mapper,
        Clock.systemUTC(),
        templateRenderer,
        new TemplateLoader(),
        new ApacheHttpCallExecutor(httpClient),
        new DefaultHttpSequenceTargetResolver(),
        redisProperties
      );
    } catch (RuntimeException | Error failure) {
      try {
        httpClient.close();
      } catch (IOException | RuntimeException | Error cleanupFailure) {
        if (failure != cleanupFailure) {
          failure.addSuppressed(cleanupFailure);
        }
      }
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
      try (httpClient; runner) {
        // Reverse declaration order closes capture first and always attempts the HTTP pool.
      }
    }
  }

  private static CloseableHttpClient newPooledClient() {
    PoolingHttpClientConnectionManager manager = new PoolingHttpClientConnectionManager();
    manager.setMaxTotal(GLOBAL_MAX_CONNECTIONS);
    manager.setDefaultMaxPerRoute(GLOBAL_MAX_PER_ROUTE);
    return HttpClients.custom().setConnectionManager(manager).build();
  }
}
