package io.pockethive.dbquery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.work.api.PocketHiveWorkerFunction;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerContext;
import io.pockethive.work.api.PocketHiveWorker;
import io.pockethive.work.api.WorkerCapability;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component(DbQueryConstants.WORKER_BEAN)
@PocketHiveWorker(
    capabilities = {WorkerCapability.MESSAGE_DRIVEN},
    config = DbQueryWorkerConfig.class
)
/**
 * Responsibility: delegate a Work invocation to configured DB query execution.
 * Must not: implement a second JDBC executor or declare Work/CP topology.
 * Contract: RESP-DB-QUERY-WORK — docs/architecture/runtime-responsibilities.md#resp-db-query-work.
 */
class DbQueryWorkerImpl implements PocketHiveWorkerFunction {

  private final DbQueryRunner runner;

  @Autowired
  DbQueryWorkerImpl(ObjectMapper mapper, DbQueryWorkerProperties properties, DbStatementExecutor executor) {
    this.runner = new DbQueryRunner(
        mapper,
        new DbQueryTemplateLoader(),
        new NamedSqlParser(),
        executor);
  }

  @Override
  public WorkItem onMessage(WorkItem item, WorkerContext context) throws Exception {
    DbQueryWorkerConfig config = context.requireConfig(DbQueryWorkerConfig.class);
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
