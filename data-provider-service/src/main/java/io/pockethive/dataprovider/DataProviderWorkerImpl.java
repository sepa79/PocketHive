package io.pockethive.dataprovider;

import io.pockethive.worker.sdk.api.PocketHiveWorkerFunction;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.config.PocketHiveWorker;
import io.pockethive.worker.sdk.config.WorkerCapability;
import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.config.WorkerOutputType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component("dataProviderWorker")
@PocketHiveWorker(
    input = WorkerInputType.REDIS_DATASET,
    output = WorkerOutputType.RABBITMQ,
    capabilities = {WorkerCapability.SCHEDULER},
    config = DataProviderWorkerConfig.class
)
class DataProviderWorkerImpl implements PocketHiveWorkerFunction {

  private final DataProviderWorkerProperties properties;

  @Autowired
  DataProviderWorkerImpl(DataProviderWorkerProperties properties) {
    this.properties = properties;
  }

  @Override
  public WorkItem onMessage(WorkItem seed, WorkerContext context) {
    DataProviderWorkerConfig defaults = properties.defaultConfig();
    DataProviderWorkerConfig config = context.config(DataProviderWorkerConfig.class);

    Map<String, Object> headers = new LinkedHashMap<>(seed.headers());
    if (defaults != null) {
      defaults.headers().forEach(headers::put);
    }
    if (config != null) {
      config.headers().forEach(headers::put);
    }
    headers.putIfAbsent("message-id", UUID.randomUUID().toString());
    headers.put("x-ph-service", context.info().role());

    context.statusPublisher().update(status -> status
        .data("enabled", context.enabled())
        .data("headers", headers.size()));

    return seed.addStep(seed.asString(), headers);
  }
}
