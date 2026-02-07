package io.pockethive.httpsequence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.requesttemplates.TemplateLoader;
import io.pockethive.worker.sdk.api.PocketHiveWorkerFunction;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.auth.AuthHeaderGenerator;
import io.pockethive.worker.sdk.config.PocketHiveWorker;
import io.pockethive.worker.sdk.config.WorkerCapability;
import io.pockethive.worker.sdk.config.RedisSequenceProperties;
import io.pockethive.worker.sdk.templating.TemplateRenderer;
import java.time.Clock;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

@Component("httpSequenceWorker")
@PocketHiveWorker(
    capabilities = {WorkerCapability.MESSAGE_DRIVEN, WorkerCapability.HTTP},
    config = HttpSequenceWorkerConfig.class
)
class HttpSequenceWorkerImpl implements PocketHiveWorkerFunction {

  private static final int GLOBAL_MAX_CONNECTIONS = 200;
  private static final int GLOBAL_MAX_PER_ROUTE = 200;

  private final HttpSequenceWorkerProperties properties;
  private final HttpSequenceRunner runner;

  @Autowired
  HttpSequenceWorkerImpl(
      ObjectMapper mapper,
      HttpSequenceWorkerProperties properties,
      TemplateRenderer templateRenderer,
      RedisSequenceProperties redisProperties,
      @Nullable AuthHeaderGenerator authHeaderGenerator
  ) {
    HttpClient pooled = newPooledClient();
    this.properties = properties;
    this.runner = new HttpSequenceRunner(
        mapper,
        Clock.systemUTC(),
        templateRenderer,
        new TemplateLoader(),
        new ApacheHttpCallExecutor(pooled),
        redisProperties,
        authHeaderGenerator
    );
  }

  @Override
  public WorkItem onMessage(WorkItem seed, WorkerContext context) {
    HttpSequenceWorkerConfig config = context.configOrDefault(HttpSequenceWorkerConfig.class, properties::defaultConfig);
    return runner.run(seed, context, config);
  }

  private static HttpClient newPooledClient() {
    PoolingHttpClientConnectionManager manager = new PoolingHttpClientConnectionManager();
    manager.setMaxTotal(GLOBAL_MAX_CONNECTIONS);
    manager.setDefaultMaxPerRoute(GLOBAL_MAX_PER_ROUTE);
    return HttpClients.custom().setConnectionManager(manager).build();
  }
}
