package io.pockethive.processor.handler;

import com.fasterxml.jackson.databind.JsonNode;
import io.pockethive.processor.ProcessorWorkerConfig;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;

public interface ProtocolHandler {
  WorkItem invoke(WorkItem message, JsonNode envelope, ProcessorWorkerConfig config, WorkerContext context) throws Exception;
}
