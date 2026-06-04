package io.pockethive.dbquery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.worker.sdk.api.PocketHiveWorkerFunction;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.config.PocketHiveWorker;
import io.pockethive.worker.sdk.config.WorkerCapability;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component(DbQueryConstants.WORKER_BEAN)
@PocketHiveWorker(
    capabilities = {WorkerCapability.MESSAGE_DRIVEN},
    config = DbQueryWorkerConfig.class
)
class DbQueryWorkerImpl implements PocketHiveWorkerFunction {

  private final DbQueryWorkerProperties properties;
  private final DbQueryRunner runner;

  @Autowired
  DbQueryWorkerImpl(ObjectMapper mapper, DbQueryWorkerProperties properties, DbStatementExecutor executor) {
    this.properties = properties;
    this.runner = new DbQueryRunner(
        mapper,
        new DbQueryTemplateLoader(),
        new NamedSqlParser(),
        executor);
  }

  @Override
  public WorkItem onMessage(WorkItem item, WorkerContext context) throws Exception {
    DbQueryWorkerConfig config = context.configOrDefault(DbQueryWorkerConfig.class, properties::defaultConfig);
    context.statusPublisher()
        .update(status -> status
            .data("adapter", config.adapter() == null ? null : config.adapter().name())
            .data("serviceId", config.serviceId())
            .data("queryId", config.queryId())
            .data("threadCount", config.threadCount())
            .data("poolMaxSize", config.pool() == null ? null : config.pool().maxSize()));
    return runner.run(item, context, config);
  }
}
