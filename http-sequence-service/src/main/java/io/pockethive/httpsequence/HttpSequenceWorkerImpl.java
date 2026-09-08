package io.pockethive.httpsequence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.requesttemplates.TemplateLoader;
import io.pockethive.work.api.PocketHiveWorkerFunction;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerContext;
import io.pockethive.work.api.PocketHiveWorker;
import io.pockethive.work.api.WorkerCapability;
import io.pockethive.worker.sdk.config.RedisSequenceProperties;
import io.pockethive.templating.api.TemplateRenderer;
import java.time.Clock;
import org.apache.hc.client5.http.classic.HttpClient;
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
 * Responsibility: delegate a Work invocation to the configured HTTP sequence.
 * Must not: own another service's lifecycle or reimplement the shared template engine.
 * Contract: RESP-HTTP-SEQUENCE-WORK — docs/architecture/runtime-responsibilities.md#resp-http-sequence-work.
 */
class HttpSequenceWorkerImpl implements PocketHiveWorkerFunction {

  private static final int GLOBAL_MAX_CONNECTIONS = 200;
  private static final int GLOBAL_MAX_PER_ROUTE = 200;

  private final HttpSequenceRunner runner;

  @Autowired
  HttpSequenceWorkerImpl(
      ObjectMapper mapper,
      HttpSequenceWorkerProperties properties,
      TemplateRenderer templateRenderer,
      RedisSequenceProperties redisProperties
  ) {
    HttpClient pooled = newPooledClient();
    this.runner = new HttpSequenceRunner(
        mapper,
        Clock.systemUTC(),
        templateRenderer,
        new TemplateLoader(),
        new ApacheHttpCallExecutor(pooled),
        new DefaultHttpSequenceTargetResolver(),
        redisProperties
    );
  }

  @Override
  public WorkItem onMessage(WorkItem seed, WorkerContext context) {
    HttpSequenceWorkerConfig config = context.requireConfig(HttpSequenceWorkerConfig.class);
    return runner.run(seed, context, config);
  }

  private static HttpClient newPooledClient() {
    PoolingHttpClientConnectionManager manager = new PoolingHttpClientConnectionManager();
    manager.setMaxTotal(GLOBAL_MAX_CONNECTIONS);
    manager.setDefaultMaxPerRoute(GLOBAL_MAX_PER_ROUTE);
    return HttpClients.custom().setConnectionManager(manager).build();
  }
}
